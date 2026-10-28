"""Source-quality validator only. Not Privacy/Fidelity/Utility PETs evaluation."""
import argparse
import csv
import hashlib
import json
from collections import Counter
from datetime import datetime, timedelta
from pathlib import Path

from generate_raw_dataset import APPS, RELATIONS, PURPOSES, SDK_RELEASE, flatten, encode


def pairs(items):
    out = {}
    for key, value in items:
        if key in out:
            raise ValueError('duplicate JSON key')
        out[key] = value
    return out


def reject_constant(_):
    raise ValueError('nonfinite JSON value')


def load(path):
    return json.loads(path.read_text(encoding='utf-8-sig'), object_pairs_hook=pairs, parse_constant=reject_constant)


def schema_errors(value, spec, path='$'):
    """Validate ONLY keywords present in source_schema.json; not a general schema engine."""
    if 'anyOf' in spec:
        return [] if any(not schema_errors(value,branch,path) for branch in spec['anyOf']) else [path+':anyOf']
    if 'oneOf' in spec:
        return [] if sum(not schema_errors(value,branch,path) for branch in spec['oneOf'])==1 else [path+':oneOf']
    mapping={'object':dict,'array':list,'string':str,'integer':int,'number':(int,float),'boolean':bool,'null':type(None)}
    if 'type' in spec:
        allowed=mapping[spec['type']]
        if type(value) not in (allowed if type(allowed) is tuple else (allowed,)):
            return [path+':type']
    errors=[]
    if 'const' in spec and (type(value) is not type(spec['const']) or value!=spec['const']):
        errors.append(path+':const')
    if type(value) is dict:
        if set(spec.get('required',[]))-set(value):
            errors.append(path+':required')
        props=spec.get('properties',{})
        if spec.get('additionalProperties') is False and set(value)-set(props):
            errors.append(path+':additionalProperties')
        for key,subspec in props.items():
            if key in value:
                errors.extend(schema_errors(value[key],subspec,path+'.'+key))
    if type(value) is list:
        for item in value:
            errors.extend(schema_errors(item,spec.get('items',{}),path+'[]'))
    return errors


def validate(source, catalog, schema=None):
    if schema is None:
        schema=load(Path(__file__).resolve().parent/'source_schema.json')
    structure=schema_errors(source,schema)
    if structure:
        return {'passed':False,'error_count':len(structure),
                'errors':[{'record_id':'dataset','code':e} for e in structure],
                'validation_kind':'RAW_SOURCE_STRUCTURAL_AND_AUTHORED_TEMPLATE_COHERENCE',
                'privacy_processing_applied':False,'checked_environments':0,'checked_tasks':0,
                'checks':{'typed_schema':1},'assertion_checks':1}
    errors = []
    checks = Counter()
    def check(ok, code, rid='dataset'):
        checks[code] += 1
        if not ok:
            errors.append({'record_id':rid,'code':code})
    root_keys = {'bundle_version','synthetic','privacy_processing_applied','provenance','generator','phone_environments','task_requests'}
    check(type(source) is dict and set(source)==root_keys,'root_keys')
    if not all(k in source for k in root_keys):
        return {'passed':False,'errors':errors,'checks':dict(checks)}
    check(source['bundle_version']=='5.0-raw' and source['synthetic'] is True and source['privacy_processing_applied'] is False,'raw_only_markers')
    envs, tasks = source['phone_environments'], source['task_requests']
    def check_fictional(obj, rid):
        if type(obj) is dict:
            for key,value in obj.items():
                sensitive_key=(key.endswith('_ref') or key.endswith('_id') and key not in ('scenario_id','template_id') or
                               key in ('display_name','manufacturer','model'))
                if sensitive_key and value is not None:
                    check(type(value) is str and value.startswith('SYN_'),'fictional_identifier_only',rid)
                check_fictional(value,rid)
        elif type(obj) is list:
            for item in obj:
                check_fictional(item,rid)
    for obj in envs+tasks:
        check_fictional(obj,obj.get('environment_id','dataset'))
    env_map = {e['environment_id']:e for e in envs}
    check(len(env_map)==len(envs),'unique_environment_id')
    check(len({t['task_id'] for t in tasks})==len(tasks),'unique_task_id')
    check(len({e['initialization_data']['user_profile']['user_id'] for e in envs})==len(envs),'unique_fictional_user_id')
    check(len(tasks)==len(envs)*27,'task_count_formula')
    check(all(v==len(envs)*3 for v in Counter(t['scenario_id'] for t in tasks).values()) and
          set(t['scenario_id'] for t in tasks)==set(PURPOSES),'balanced_nine_scenarios')
    message_templates = {t['template_id']:t for t in catalog['message_templates']}
    templates = {t['template_id']:t for ts in catalog['scenario_templates'].values() for t in ts}
    indexes = {}
    for env in envs:
        eid, state, init = env['environment_id'], env['continuous_state_data'], env['initialization_data']
        check(set(env)=={'environment_id','captured_offset_sec','initialization_data','continuous_state_data'},'environment_keys',eid)
        check(type(env['captured_offset_sec']) is int and env['captured_offset_sec']>=0,'captured_time_type',eid)
        check(set(init)=={'user_profile','device','contacts','installed_apps','saved_destinations'},'initialization_keys',eid)
        check(set(state)=={'permissions','battery','network','system','screen','photos','messages','notifications','calendar','location','clipboard','call','app_usage'},'state_keys',eid)
        device = init['device']
        check(type(device['sdk_int']) is int and SDK_RELEASE.get(device['sdk_int'])==device['android_release'],'android_version_mapping',eid)
        check(init['user_profile']['age_band'] in ('65-69','70-74','75-79','80-84','85-89','90+'),'age_band',eid)
        check(all(v in ('granted','denied','unknown') for v in state['permissions'].values()),'permission_values',eid)
        check(1<=state['battery']['capacity_percent']<=100,'battery_range',eid)
        current, maximum = state['system']['ring_volume_current'], state['system']['ring_volume_max']
        check(current is None or type(current) is int and 0<=current<=maximum,'volume_range',eid)
        network = state['network']
        check((network['connected'] is True and network['transport'] in ('WIFI','CELLULAR') and network['internet_validated'] is True) or
              (network['connected'] is False and network['transport']=='NONE' and network['internet_validated'] is False) or
              (network['connected'] is None and network['transport']=='UNKNOWN' and network['internet_validated'] is None),'network_consistency',eid)
        check(state['location']['latitude']==0 and state['location']['longitude']==0 and
              state['location']['coordinate_kind']=='PLACEHOLDER_NOT_REAL_POSITION','location_placeholder',eid)
        resources = {}
        for bucket,key in [('contacts','contact_id'),('installed_apps','app_id'),('saved_destinations','destination_id'),
                            ('photos','photo_id'),('messages','message_id'),('notifications','notification_id'),('calendar','event_id')]:
            records = init[bucket] if bucket in init else state[bucket]
            resources[bucket] = {r[key]:r for r in records}
            check(len(resources[bucket])==len(records),'unique_'+key,eid)
        indexes[eid]=resources
        apps = resources['installed_apps']
        check(len({a['name'] for a in apps.values()})==len(apps),'unique_app_name',eid)
        check(state['screen']['current_app_id'] in apps and apps[state['screen']['current_app_id']]['installed'],'screen_installed_app',eid)
        for photo in state['photos']:
            check(photo['taken_offset_sec']<=env['captured_offset_sec'],'photo_time',eid)
            check(photo['pixels_available'] is False,'no_photo_pixels',eid)
        for dest in init['saved_destinations']:
            check(dest['latitude']==0 and dest['longitude']==0 and dest['coordinate_kind']=='PLACEHOLDER_NOT_GEOCODABLE','destination_placeholder',eid)
        for message in state['messages']:
            template = message_templates.get(message['template_id'])
            check(template is not None,'message_template_exists',eid)
            check(message['received_offset_sec']<=env['captured_offset_sec'],'message_time',eid)
            if template:
                # Source text must be one of the authored fictional patterns. No arbitrary real text.
                suffix = message['message_id'].split('_M_')[-1]
                envno,k = suffix.split('_')
                expected = [p.format(i=f'{int(envno):04d}_{int(k):02d}') for p in template['text_patterns']]
                check(message['text'] in expected,'fictional_message_pattern',eid)
                check(message['source_category']==template['source_category'],'message_source_category',eid)
                check(message['context_sufficient']==('insufficient_context' not in template['semantic_tags']),'message_context_label',eid)
        for notif in state['notifications']:
            check(notif['app_id'] in apps and apps[notif['app_id']]['installed'],'notification_app_reference',eid)
            check(notif['message_id'] in resources['messages'],'notification_message_reference',eid)
            if notif['message_id'] in resources['messages']:
                check(resources['messages'][notif['message_id']]['received_offset_sec']<=notif['post_offset_sec']<=env['captured_offset_sec'],'notification_time',eid)
    for task in tasks:
        tid, sc, params = task['task_id'],task['scenario_id'],task['parameters']
        check(set(task)=={'task_id','environment_id','user_id','scenario_id','subscenario','template_id','utterance_pattern_index',
                         'utterance','privacy_mode','grants','confirmation','cancelled','followup_utterance',
                         'requested_offset_sec','time_anchor','parameters','fixture_labels'},'task_keys',tid)
        check(task['environment_id'] in env_map,'task_environment_reference',tid)
        if task['environment_id'] not in env_map:
            continue
        env = env_map[task['environment_id']]
        resources = indexes[task['environment_id']]
        init,state = env['initialization_data'],env['continuous_state_data']
        check(task['user_id']==init['user_profile']['user_id'],'task_user_reference',tid)
        check(type(task['requested_offset_sec']) is int and task['requested_offset_sec']>=env['captured_offset_sec'],'task_time',tid)
        check(task['privacy_mode'] in ('STRICT_LOCAL','MINIMIZED_CLOUD'),'privacy_mode',tid)
        check(task['confirmation'] in ('approved','pending','rejected','not_required'),'confirmation_values',tid)
        check(type(task['cancelled']) is bool and (task['followup_utterance'] is not None)==task['cancelled'],'cancel_followup',tid)
        check(len(task['grants'])==len(set(task['grants'])) and set(task['grants']).issubset(set(PURPOSES.get(sc,[]))|{'cloud_features'}),'grant_values',tid)
        check(task['privacy_mode']!='STRICT_LOCAL' or 'cloud_features' not in task['grants'],'no_cloud_grant_for_strict_local',tid)
        check(task['fixture_labels']['cloud_features_consent']==('cloud_features' in task['grants']),'cloud_consent_label',tid)
        age=task['requested_offset_sec']-env['captured_offset_sec']
        check(task['fixture_labels']['snapshot_age_sec']==age and
              task['fixture_labels']['snapshot_freshness_fixture']==('stale_over_300sec' if age>300 else 'within_300sec'),'snapshot_age_label',tid)
        check(task['fixture_labels']['privacy_processing_applied'] is False and
              task['fixture_labels']['phone_execution_verified'] is False,'task_unprocessed_markers',tid)
        template=templates.get(task['template_id'])
        check(template is not None and task['template_id'].startswith(sc+'_'),'task_template_reference',tid)
        if not template:
            continue
        check(task['subscenario']==template['subscenario'],'task_subscenario',tid)
        value=template['semantic_value']
        pi=task['utterance_pattern_index']
        check(type(pi) is int and 0<=pi<len(template['utterance_patterns']),'pattern_index',tid)
        if type(pi) is not int or not 0<=pi<len(template['utterance_patterns']):
            continue
        relation=params.get('recipient_relation')
        expected=template['utterance_patterns'][pi].format(recipient=RELATIONS.get(relation,''))
        check(task['utterance']==expected,'utterance_parameter_alignment',tid)
        check(relation is None or relation in RELATIONS,'recipient_relation_values',tid)
        for param,bucket in [('photo_id','photos'),('message_id','messages'),('notification_id','notifications'),('destination_ref','saved_destinations')]:
            check(params.get(param) is None or params[param] in resources[bucket],'selected_'+param+'_reference',tid)
        selected_missing=any(k in params and params[k] is None for k in ['photo_id','message_id','notification_id','destination_ref'])
        check(task['fixture_labels']['selected_resource_missing']==selected_missing,'selected_missing_label',tid)
        if relation:
            n=sum(c['relationship']==relation for c in init['contacts'])
            check(task['fixture_labels']['recipient_binding_case']==('missing' if n==0 else 'unique' if n==1 else 'ambiguous'),'recipient_binding_label',tid)
        if sc=='SEND_MESSAGE':
            check(params.get('message_text')==value and params.get('app_name')=='LINE','message_draft_semantics',tid)
        if sc=='OPEN_APP':
            check(params.get('app_name')==value,'app_intent_semantics',tid)
        if sc=='CALL_CONTACT':
            check(params.get('channel')=='PHONE' and params.get('requested_scope')==value,'call_intent_semantics',tid)
            check(value!='local_only' or task['privacy_mode']=='STRICT_LOCAL','explicit_local_request',tid)
        if sc=='RESCUE_PHONE':
            check(params.get('symptom_category')==value,'rescue_intent_semantics',tid)
        if sc=='SHARE_PHOTO':
            photo=resources['photos'].get(params.get('photo_id'))
            check(params.get('selection_method')==value and params.get('app_name')=='LINE','photo_intent_semantics',tid)
            if photo:
                if value=='latest':
                    check(photo['taken_offset_sec']==max(p['taken_offset_sec'] for p in state['photos']),'latest_photo_selection',tid)
                if value in ('family_album','outing_album'):
                    check(photo['album_category']==value,'photo_album_selection',tid)
                if task['subscenario']=='location_metadata':
                    check(photo['exif_location_ref'] is not None,'photo_exif_fixture',tid)
                if task['subscenario']=='private_background':
                    check(photo['background_privacy_hint']=='synthetic_private_items','photo_background_fixture',tid)
        if sc=='NAVIGATE_TO_PLACE':
            check(params.get('destination_category')==value and params.get('app_name')=='MAPS','destination_intent_semantics',tid)
            dest=resources['saved_destinations'].get(params.get('destination_ref'))
            if dest:
                check(dest['category']==value,'destination_category_reference',tid)
        if sc=='SET_REMINDER':
            anchor=datetime.fromisoformat(task['time_anchor'])
            check(params.get('reminder_kind')==value[0],'reminder_kind_semantics',tid)
            when=(anchor+timedelta(seconds=value[2])) if value[1]=='relative' else (anchor+timedelta(days=value[1])).replace(hour=value[2],minute=value[3],second=0,microsecond=0)
            expected_spec=({'kind':'relative_seconds','seconds':value[2]} if value[1]=='relative' else
                           {'kind':'local_day_time','day_offset':value[1],'hour':value[2],'minute':value[3]})
            check(params.get('time_spec')==expected_spec and params.get('scheduled_at')==when.isoformat() and params.get('timezone')=='Asia/Taipei','reminder_time_alignment',tid)
        if sc=='READ_NOTIFICATION':
            notif=resources['notifications'].get(params.get('notification_id'))
            candidates=[n for n in state['notifications'] if value not in APPS or resources['installed_apps'][n['app_id']]['name']==value]
            check(params.get('source_app')==(value if value in APPS else None),'notification_app_semantics',tid)
            if notif:
                check(notif in candidates,'notification_app_selection',tid)
                if value!='selected':
                    check(notif['post_offset_sec']==max(n['post_offset_sec'] for n in candidates),'latest_notification_selection',tid)
        if sc=='CHECK_MESSAGE':
            message=resources['messages'].get(params.get('message_id'))
            if message:
                mt=message_templates[message['template_id']]
                tags=mt['semantic_tags']
                scene=task['subscenario']
                aligned=(scene=='general_risk' or scene=='sender_unknown' and message['source_category']=='unknown' or
                         scene=='link_review' and 'link' in tags or scene=='money_request' and any(t in tags for t in ['transfer','payment']) or
                         scene=='otp_request' and 'otp' in tags or scene=='official_impersonation' and 'impersonation' in tags or
                         scene=='family_impersonation' and 'family_claim' in tags or
                         scene=='benign_or_uncertain' and message['source_category']!='unknown' and
                         not any(t in tags for t in ('payment','transfer','otp','credential','impersonation')))
                check(aligned,'selected_message_semantics',tid)
    return {'passed':not errors,'validation_kind':'RAW_SOURCE_STRUCTURAL_AND_AUTHORED_TEMPLATE_COHERENCE',
            'privacy_processing_applied':False,'checked_environments':len(envs),'checked_tasks':len(tasks),
            'error_count':len(errors),'errors':errors,'assertion_checks':sum(checks.values()),'checks':dict(checks),
            'limitations':['Checks use the same authored semantic specifications as generation, not an independent task-planning oracle.',
                           'Not a Privacy, population Fidelity or Agent Utility effectiveness result.']}


def csv_check(path, records):
    with path.open(encoding='utf-8-sig',newline='') as handle:
        reader=csv.DictReader(handle)
        headers=reader.fieldnames
        rows=list(reader)
    mismatches=[]
    for i,(row,record) in enumerate(zip(rows,records),1):
        flat=flatten(record)
        expected={h:encode(flat[h]) if h in flat else '' for h in headers}
        if row!=expected or set(flat)-set(headers):
            mismatches.append(i)
    return {'passed':len(rows)==len(records) and len(headers)==len(set(headers)) and not mismatches,
            'rows':len(rows),'json_rows':len(records),'columns':len(headers),'mismatch_rows':mismatches}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('dataset',type=Path)
    parser.add_argument('--report',type=Path,required=True)
    args=parser.parse_args()
    source=load(args.dataset/'source_raw_v5.json')
    catalog=load(Path(__file__).resolve().parent/'template_catalog.json')
    report=validate(source,catalog)
    if report['checks'].get('typed_schema'):
        report['csv_json_roundtrip']={'skipped':'Input failed typed schema'}
        with args.report.open('x',encoding='utf-8',newline='\n') as handle:
            json.dump(report,handle,ensure_ascii=False,indent=2);handle.write('\n')
        print(json.dumps({'passed':False,'error_count':report['error_count']},ensure_ascii=False))
        raise SystemExit(1)
    report['csv_json_roundtrip']={name:csv_check(args.dataset/name,records) for name,records in
                                [('phone_environments.csv',source['phone_environments']),('task_requests.csv',source['task_requests'])]}
    report['passed']=report['passed'] and all(r['passed'] for r in report['csv_json_roundtrip'].values())
    report['age_band_counts']=dict(Counter(e['initialization_data']['user_profile']['age_band'] for e in source['phone_environments']))
    report['sdk_counts']=dict(Counter(e['initialization_data']['device']['sdk_int'] for e in source['phone_environments']))
    report['fixture_counts']={key:dict(Counter(str(t['fixture_labels'][key]) for t in source['task_requests'])) for key in
                              ['recipient_binding_case','selected_resource_missing','grant_case','cloud_features_consent','snapshot_freshness_fixture']}
    report['privacy_modes']=dict(Counter(t['privacy_mode'] for t in source['task_requests']))
    report['confirmation']=dict(Counter(t['confirmation'] for t in source['task_requests']))
    report['cancelled_tasks']=sum(t['cancelled'] for t in source['task_requests'])
    input_names=['source_raw_v5.json','phone_environments.csv','task_requests.csv','scenario_catalog.csv','generation_report.json']
    report['input_sha256']={name:hashlib.sha256((args.dataset/name).read_bytes()).hexdigest() for name in input_names}
    report['validation_tools_sha256']={name:hashlib.sha256((Path(__file__).resolve().parent/name).read_bytes()).hexdigest()
                                     for name in ['validate_raw_dataset.py','source_schema.json','template_catalog.json']}
    with args.report.open('x',encoding='utf-8',newline='\n') as handle:
        json.dump(report,handle,ensure_ascii=False,indent=2)
        handle.write('\n')
    print(json.dumps({k:report[k] for k in ['passed','checked_environments','checked_tasks','error_count','assertion_checks','csv_json_roundtrip']},ensure_ascii=False))
    raise SystemExit(0 if report['passed'] else 1)


if __name__=='__main__':
    main()
