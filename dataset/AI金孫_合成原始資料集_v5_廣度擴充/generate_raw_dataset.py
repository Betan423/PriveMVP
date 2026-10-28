"""Offline raw synthetic fixture generator. Never invokes an LLM or Privacy Engine."""
import argparse
import csv
import hashlib
import json
import random
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path

BASE = Path(__file__).resolve().parent
VERSION = '5.0-raw'
RELATIONS = {'daughter': '女兒', 'son': '兒子', 'friend': '朋友', 'granddaughter': '孫女',
             'grandson': '孫子', 'neighbor': '鄰居', 'caregiver': '照顧者', 'doctor': '醫師'}
APPS = ['LINE', 'GALLERY', 'SETTINGS', 'MAPS', 'CALENDAR', 'MESSAGES', 'PHONE', 'CAMERA']
SDK_RELEASE = {29: '10', 30: '11', 31: '12', 32: '12', 33: '13', 34: '14', 35: '15', 36: '16'}
DESTINATIONS = ['hospital', 'station', 'friend_home', 'park', 'home', 'clinic', 'community', 'store']
PURPOSES = {
 'SHARE_PHOTO': ['voice_command', 'recipient_binding', 'selected_photo', 'share_app_state'],
 'CHECK_MESSAGE': ['voice_command', 'selected_message'],
 'RESCUE_PHONE': ['voice_command', 'system_status', 'network_state'],
 'CALL_CONTACT': ['voice_command', 'recipient_binding', 'call_state'],
 'SEND_MESSAGE': ['voice_command', 'recipient_binding', 'message_draft', 'share_app_state'],
 'READ_NOTIFICATION': ['voice_command', 'selected_notification'],
 'SET_REMINDER': ['voice_command', 'reminder_details', 'calendar_state'],
 'NAVIGATE_TO_PLACE': ['voice_command', 'selected_destination', 'location_state', 'maps_state'],
 'OPEN_APP': ['voice_command', 'selected_app', 'screen_state']}


def dump(path, obj):
    with path.open('x', encoding='utf-8', newline='\n') as handle:
        json.dump(obj, handle, ensure_ascii=False, indent=2)
        handle.write('\n')


def flatten(obj, prefix=''):
    result = {}
    for key, value in obj.items():
        name = prefix + '.' + key if prefix else key
        if type(value) is dict:
            result.update(flatten(value, name))
        else:
            result[name] = value
    return result


def encode(value):
    if type(value) in (dict, list):
        return json.dumps(value, ensure_ascii=False, separators=(',', ':'))
    if value is None:
        return 'null'
    if type(value) is bool:
        return str(value).lower()
    return str(value)


def write_csv(path, records):
    flattened = [flatten(row) for row in records]
    headers = list(dict.fromkeys(key for row in flattened for key in row))
    with path.open('x', encoding='utf-8-sig', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=headers)
        writer.writeheader()
        writer.writerows({key: encode(row[key]) if key in row else '' for key in headers} for row in flattened)


def environment(i, rng, catalog):
    eid = f'SYN_V5_ENV{i:04d}'
    ref = lambda kind, index='': f'SYN_V5_{kind}_{i:04d}' + (f'_{index}' if index != '' else '')
    captured = 600 + i * 30
    contacts = []
    for k, relation in enumerate(RELATIONS):
        if i % 16 == 0 or (i + k) % 13 == 0:
            continue
        contacts.append({'contact_id': ref('C', k), 'display_name': ref('NAME_PRIVATE', k),
                         'relationship': relation, 'phone_ref': ref('PHONE_PRIVATE', k) if (i+k) % 11 else None,
                         'line_ref': ref('LINE_PRIVATE', k) if (i+k) % 9 else None})
    if contacts and i % 7 == 0:
        contacts.append({**contacts[0], 'contact_id': ref('C_DUP'), 'display_name': ref('NAME_PRIVATE_DUP'),
                         'phone_ref': ref('PHONE_PRIVATE_DUP'), 'line_ref': ref('LINE_PRIVATE_DUP')})
    apps = [{'app_id': ref('APP', name), 'name': name,
             'package': 'jp.naver.line.android' if name == 'LINE' else 'com.example.synthetic.' + name.lower(),
             'installed': name == 'SETTINGS' or (i + k) % 17 != 0} for k, name in enumerate(APPS)]
    photos = []
    for k in range(0 if i % 16 == 0 else 2 + i % 7):
        photos.append({'photo_id': ref('P', k), 'display_name': ref('PHOTO', k) + '.jpg',
                       'media_ref': ref('MEDIA_PRIVATE', k), 'taken_offset_sec': captured - (k+1)*19,
                       'album_category': 'family_album' if k % 2 == 0 else 'outing_album',
                       'mime_type': 'image/jpeg', 'size_bytes': rng.randint(100, 8000) * 1024,
                       'width': rng.choice([720, 1080, 1440]), 'height': rng.choice([720, 1080, 1920]),
                       'exif_location_ref': ref('LOCATION_PRIVATE') if k % 3 == 0 else None,
                       'background_privacy_hint': 'synthetic_private_items' if k % 4 == 0 else 'unknown',
                       'pixels_available': False})
    messages = []
    for k, template in enumerate(catalog['message_templates']):
        text = template['text_patterns'][(i + k) % len(template['text_patterns'])].format(i=f'{i:04d}_{k:02d}')
        messages.append({'message_id': ref('M', k), 'sender_ref': ref('SENDER_PRIVATE', k),
                         'source_category': template['source_category'], 'text': text,
                         'received_offset_sec': captured - (len(catalog['message_templates']) - k) * 5,
                         'context_sufficient': 'insufficient_context' not in template['semantic_tags'],
                         'template_id': template['template_id']})
    notifications = []
    for k, message in enumerate(messages):
        app = next(a for a in apps if a['name'] == ['LINE', 'MESSAGES', 'CALENDAR'][k % 3])
        if app['installed'] and i % 19 != 0:
            notifications.append({'notification_id': ref('N', k), 'app_id': app['app_id'],
                                  'message_id': message['message_id'], 'post_offset_sec': message['received_offset_sec'] + 1,
                                  'title_ref': ref('NOTIFICATION_TITLE_PRIVATE', k)})
    destinations = [{'destination_id': ref('DEST', kind), 'category': kind,
                     'display_name': ref('PLACE_PRIVATE', kind), 'address_ref': ref('ADDRESS_PRIVATE', kind),
                     'latitude': 0, 'longitude': 0, 'coordinate_kind': 'PLACEHOLDER_NOT_GEOCODABLE'}
                    for k, kind in enumerate(DESTINATIONS) if (i + k) % 11 != 0]
    airborne = i % 13 == 0
    connected = None if i % 23 == 0 and not airborne else False if airborne or i % 9 == 0 else True
    transport = 'UNKNOWN' if connected is None else 'NONE' if not connected else ('WIFI' if i % 2 == 0 else 'CELLULAR')
    sdk = list(SDK_RELEASE)[(i-1) % len(SDK_RELEASE)]
    maximum = [5, 7, 15, 25][i % 4]
    current = None if i % 10 == 0 else [0, 1, maximum // 2, maximum][i % 4]
    return {'environment_id': eid, 'captured_offset_sec': captured,
      'initialization_data': {
       'user_profile': {'user_id': ref('U'), 'age_band': ['65-69','70-74','75-79','80-84','85-89','90+'][(i-1)%6],
                        'locale': 'zh-TW', 'font_scale': [1.0,1.15,1.3,1.5,1.75,2.0][(i-1)%6],
                        'assistance_preference': ['voice_first','guided_steps','confirm_every_action'][(i-1)%3]},
       'device': {'device_id': ref('DEVICE_PRIVATE'), 'manufacturer': 'SYN_BRAND_' + str(i % 4),
                  'model': 'SYN_MODEL_' + str(i % 12), 'sdk_int': sdk, 'android_release': SDK_RELEASE[sdk],
                  'screen_width_px': [720,1080,1440][i % 3], 'screen_height_px': [1600,1920,2400][i % 3],
                  'ram_gb': [4,6,8,12][i % 4], 'storage_gb': [64,128,256,512][i % 4]},
       'contacts': contacts, 'installed_apps': apps, 'saved_destinations': destinations},
      'continuous_state_data': {
       'permissions': {name: ['granted','granted','denied','unknown'][(i+k)%4]
                       for k,name in enumerate(['contacts','media_images','notifications','accessibility',
                                                'microphone','location','calendar','call_phone'])},
       'battery': {'capacity_percent': rng.randint(1,100), 'charging': i % 3 == 0},
       'network': {'connected': connected, 'transport': transport, 'internet_validated': connected,
                   'wifi_ssid_ref': ref('WIFI_PRIVATE') if transport == 'WIFI' else None},
       'system': {'airplane_mode': 'on' if airborne else 'off', 'dnd_mode': ['off','on','unknown'][i % 3],
                  'ringer_mode': ['normal','silent','vibrate','unknown'][i % 4],
                  'ring_volume_current': current, 'ring_volume_max': maximum},
       'screen': {'current_app_id': rng.choice([a['app_id'] for a in apps if a['installed']]),
                  'guard_ready': i % 7 != 0, 'locked': i % 12 == 0,
                  'node_count': rng.randint(0,150), 'text_ref': ref('SCREEN_PRIVATE')},
       'photos': photos, 'messages': messages, 'notifications': notifications,
       'calendar': [{'event_id': ref('EVENT', k), 'title_ref': ref('CALENDAR_PRIVATE', k),
                     'start_offset_sec': captured + 3600 * (k+1)} for k in range(i % 5)],
       'location': {'location_ref': ref('LOCATION_PRIVATE'), 'latitude': 0, 'longitude': 0,
                    'accuracy_m': [10,50,250,None][i % 4], 'coordinate_kind': 'PLACEHOLDER_NOT_REAL_POSITION'},
       'clipboard': {'observability': 'unavailable', 'text_ref': None},
       'call': {'state': ['idle','ringing','offhook','unknown'][i % 4], 'caller_ref': None},
       'app_usage': [{'app_id': a['app_id'], 'foreground_sec': rng.randint(0,3600),
                      'last_used_offset_sec': captured - rng.randint(0,300)} for a in apps if a['installed']]}}


def choose_message(scene, state, catalog, rng):
    templates = {t['template_id']: t for t in catalog['message_templates']}
    candidates = []
    for message in state['messages']:
        tags = templates[message['template_id']]['semantic_tags']
        ok = (scene in ('general_risk',) or
              scene == 'sender_unknown' and message['source_category'] == 'unknown' or
              scene == 'link_review' and 'link' in tags or
              scene == 'money_request' and any(t in tags for t in ('transfer','payment')) or
              scene == 'otp_request' and 'otp' in tags or
              scene == 'official_impersonation' and 'impersonation' in tags or
              scene == 'family_impersonation' and 'family_claim' in tags or
              scene == 'benign_or_uncertain' and message['source_category'] != 'unknown' and
              not any(t in tags for t in ('payment','transfer','otp','credential','impersonation')))
        if ok:
            candidates.append(message)
    return rng.choice(candidates) if candidates else None


def task(env, i, sc, rep, catalog, rng, counter):
    templates = catalog['scenario_templates'][sc]
    ti = ((i-1)*3 + rep) % len(templates)
    template = templates[ti]
    value = template['semantic_value']
    pattern_index = (((i-1)*3 + rep) // len(templates)) % len(template['utterance_patterns'])
    pattern = template['utterance_patterns'][pattern_index]
    relation = list(RELATIONS)[(i + rep) % len(RELATIONS)]
    params = {}
    state, init = env['continuous_state_data'], env['initialization_data']
    requested = env['captured_offset_sec'] + [10,90,900][rep]
    anchor = datetime(2026,10,12,8,0,tzinfo=timezone(timedelta(hours=8))) + timedelta(seconds=requested)
    utterance = pattern.format(recipient=RELATIONS[relation])
    if sc in ('SHARE_PHOTO','CALL_CONTACT','SEND_MESSAGE'):
        params['recipient_relation'] = relation
    if sc == 'SHARE_PHOTO':
        candidates = [p for p in state['photos'] if value not in ('family_album','outing_album') or p['album_category'] == value]
        if template['subscenario'] == 'location_metadata':
            candidates = [p for p in candidates if p['exif_location_ref'] is not None]
        if template['subscenario'] == 'private_background':
            candidates = [p for p in candidates if p['background_privacy_hint'] == 'synthetic_private_items']
        photo = max(candidates, key=lambda p:p['taken_offset_sec']) if candidates and value == 'latest' else rng.choice(candidates) if candidates else None
        params.update(photo_id=photo['photo_id'] if photo else None, selection_method=value, app_name='LINE')
    elif sc == 'CHECK_MESSAGE':
        message = choose_message(template['subscenario'], state, catalog, rng)
        # Missing selection is intentional and separately annotated, not an invalid dangling reference.
        if (i+rep) % 19 == 0:
            message = None
        params.update(message_id=message['message_id'] if message else None)
    elif sc == 'RESCUE_PHONE':
        params.update(symptom_category=value, requested_scope='inspect_only' if template['subscenario'] == 'check_only' else 'guided_help')
    elif sc == 'CALL_CONTACT':
        params.update(channel='PHONE', requested_scope=value)
    elif sc == 'SEND_MESSAGE':
        params.update(message_text=value, app_name='LINE')
    elif sc == 'READ_NOTIFICATION':
        candidates = [n for n in state['notifications'] if value not in APPS or
                      next(a['name'] for a in init['installed_apps'] if a['app_id']==n['app_id']) == value]
        selected = (max(candidates, key=lambda n:n['post_offset_sec']) if candidates and value != 'selected'
                    else rng.choice(candidates) if candidates else None)
        params.update(notification_id=selected['notification_id'] if selected else None,
                      selection_method='selected' if value=='selected' else 'latest',
                      source_app=None if value in ('selected','latest') else value,
                      requested_scope='read_only')
    elif sc == 'SET_REMINDER':
        kind = value[0]
        if value[1] == 'relative':
            when = anchor + timedelta(seconds=value[2])
            spec = {'kind':'relative_seconds','seconds':value[2]}
        else:
            when = (anchor + timedelta(days=value[1])).replace(hour=value[2],minute=value[3],second=0,microsecond=0)
            spec = {'kind':'local_day_time','day_offset':value[1],'hour':value[2],'minute':value[3]}
        params.update(reminder_kind=kind, time_spec=spec, scheduled_at=when.isoformat(), timezone='Asia/Taipei')
    elif sc == 'NAVIGATE_TO_PLACE':
        matches = [d for d in init['saved_destinations'] if d['category']==value]
        params.update(destination_category=value, destination_ref=matches[0]['destination_id'] if matches else None,
                      app_name='MAPS', route_mode='walking')
    elif sc == 'OPEN_APP':
        params.update(app_name=value)
    confirmation = ['approved','pending','rejected','not_required'][(i+rep+list(PURPOSES).index(sc))%4]
    grants = list(PURPOSES[sc])
    grant_case = ['full','missing_purpose','voice_only','none','full','full'][(i+rep+list(PURPOSES).index(sc))%6]
    if grant_case == 'missing_purpose':
        grants.remove(PURPOSES[sc][-1])
    elif grant_case == 'voice_only':
        grants = ['voice_command']
    elif grant_case == 'none':
        grants = []
    mode = 'STRICT_LOCAL' if (i+rep)%3==0 or value=='local_only' else 'MINIMIZED_CLOUD'
    cloud_consented = mode == 'MINIMIZED_CLOUD' and (i+rep)%5!=0
    if cloud_consented:
        grants.append('cloud_features')
    cancelled = (counter % 17 == 0)
    candidates = [c for c in init['contacts'] if c['relationship']==params.get('recipient_relation')]
    resource_case = ('missing' if not candidates else 'ambiguous' if len(candidates)>1 else 'unique') if 'recipient_relation' in params else 'not_applicable'
    labels = {'recipient_binding_case':resource_case,
              'selected_resource_missing': any(k in params and params[k] is None for k in ['photo_id','message_id','notification_id','destination_ref']),
              'grant_case':grant_case, 'cloud_features_consent':cloud_consented,
              'snapshot_age_sec':requested-env['captured_offset_sec'],
              'snapshot_freshness_fixture': 'stale_over_300sec' if requested-env['captured_offset_sec']>300 else 'within_300sec',
              'interpretation_status':'AUTHORED_SOURCE_SEMANTICS_NOT_ENGINE_DECISION',
              'phone_execution_verified':False, 'privacy_processing_applied':False}
    return {'task_id':f'SYN_V5_T{counter:06d}', 'environment_id':env['environment_id'],
            'user_id':init['user_profile']['user_id'], 'scenario_id':sc,
            'subscenario':template['subscenario'], 'template_id':template['template_id'],
            'utterance_pattern_index':pattern_index, 'utterance':utterance, 'privacy_mode':mode,
            'grants':grants, 'confirmation':confirmation, 'cancelled':cancelled,
            'followup_utterance':'不用了，先取消。' if cancelled else None,
            'requested_offset_sec':requested, 'time_anchor':anchor.isoformat(), 'parameters':params,
            'fixture_labels':labels}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--environments', type=int, default=192)
    parser.add_argument('--seed', type=int, default=20261012)
    args = parser.parse_args(argv)
    if args.output.exists():
        raise FileExistsError('Existing output refused; choose a new folder')
    if not 32 <= args.environments <= 1000:
        raise ValueError('environments must be 32..1000')
    catalog_path = BASE / 'template_catalog.json'
    catalog = json.loads(catalog_path.read_text(encoding='utf-8'))
    rng = random.Random(args.seed)
    envs = [environment(i,rng,catalog) for i in range(1,args.environments+1)]
    tasks = []
    for i,env in enumerate(envs,1):
        for sc in PURPOSES:
            for rep in range(3):
                tasks.append(task(env,i,sc,rep,catalog,rng,len(tasks)+1))
    source = {'bundle_version':VERSION,'synthetic':True,'privacy_processing_applied':False,
              'provenance':'AI_AUTHORED_TEMPLATES_DETERMINISTIC_FICTIONAL_RAW_NOT_REAL_PEOPLE',
              'generator':{'version':VERSION,'seed':args.seed,'environments':args.environments,
                           'tasks_per_environment':27,'per_row_llm_calls':0,
                           'template_catalog_sha256':hashlib.sha256(catalog_path.read_bytes()).hexdigest(),
                           'encoding':'UTF-8 JSON / UTF-8 BOM CSV'},
              'phone_environments':envs,'task_requests':tasks}
    args.output.mkdir(parents=True)
    dump(args.output/'source_raw_v5.json',source)
    write_csv(args.output/'phone_environments.csv',envs)
    write_csv(args.output/'task_requests.csv',tasks)
    write_csv(args.output/'scenario_catalog.csv',[
       {'scenario_id':sc,'subscenario':t['subscenario'],'template_id':t['template_id'],
        'phrase_patterns':len(t['utterance_patterns']),
        'task_count':sum(r['template_id']==t['template_id'] for r in tasks),
        'processing_status':'RAW_NOT_PRIVACY_PROCESSED'}
       for sc,templates in catalog['scenario_templates'].items() for t in templates])
    dump(args.output/'generation_report.json',{
         'version':VERSION,'synthetic':True,'privacy_processing_applied':False,
         'environment_count':len(envs),'task_count':len(tasks),
         'scenario_counts':dict(Counter(t['scenario_id'] for t in tasks)),
         'subscenario_count':len({t['template_id'] for t in tasks}),
         'utterance_pattern_count':sum(len(t['utterance_patterns']) for ts in catalog['scenario_templates'].values() for t in ts),
         'unique_utterances':len({t['utterance'] for t in tasks}),
         'message_family_count':len(catalog['message_templates']),
         'message_pattern_count':sum(len(t['text_patterns']) for t in catalog['message_templates']),
         'actual_network_calls':0,'per_row_llm_calls':0,'actual_phone_actions':0,
         'new_privacy_derived_tasks':0,'success_rate':None,
         'generator_configuration':source['generator'],
         'limitations':['Source quality checks are not PETs effectiveness evaluation.',
                        'No distributional fidelity to real older adults established.',
                        'Repeated template variants are correlated, not independent user samples.',
                        'All 5184 default tasks are newly generated; old v4 576 and v3 3-row derived baseline are not merged.']})
    print(json.dumps({'output':str(args.output.resolve()),'environments':len(envs),'raw_tasks':len(tasks)},ensure_ascii=False))
    return source


if __name__=='__main__':
    main()
