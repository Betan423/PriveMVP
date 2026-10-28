"""Read-only, reproducible intake audit. No Privacy Engine processing or external calls."""
import argparse
import csv
import hashlib
import importlib
import json
import os
import re
import shutil
import subprocess
import sys
from collections import Counter
from pathlib import Path
from zipfile import ZipFile
from xml.etree import ElementTree as ET

sys.dont_write_bytecode = True


def strict_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate JSON key')
        result[key] = value
    return result


def reject_constant(value):
    raise ValueError('nonfinite JSON value')


def load_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'), object_pairs_hook=strict_pairs,
                      parse_constant=reject_constant)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_csv(path):
    with path.open(encoding='utf-8-sig', newline='') as handle:
        reader = csv.DictReader(handle)
        headers = reader.fieldnames
        if len(headers) != len(set(headers)):
            raise ValueError('duplicate CSV header')
        rows = list(reader)
        if any(None in row or any(v is None for v in row.values()) for row in rows):
            raise ValueError('CSV row width mismatch')
        return headers, rows


def flatten(obj, prefix=''):
    result = {}
    for key, value in obj.items():
        name = prefix + '.' + key if prefix else key
        if type(value) is dict:
            result.update(flatten(value, name))
        else:
            result[name] = value
    return result


def cell(value):
    if type(value) is list or type(value) is dict:
        return json.dumps(value, ensure_ascii=False, separators=(',', ':'))
    if value is None:
        return 'null'
    if type(value) is bool:
        return str(value).lower()
    return str(value)


def compare_csv(path, records):
    headers, rows = read_csv(path)
    mismatches = []
    for index, (row, record) in enumerate(zip(rows, records), 1):
        fields = flatten(record)
        for key in headers:
            expected = cell(fields[key]) if key in fields else ''
            if row[key] != expected:
                mismatches.append({'row': index, 'column': key})
        for key in fields:
            if key not in headers:
                mismatches.append({'row': index, 'omitted_json_field': key})
    return {'rows': len(rows), 'json_rows': len(records), 'columns': len(headers),
            'matched': len(rows) == len(records) and not mismatches,
            'mismatches': mismatches}


def predicate_errors(value, spec, path='$'):
    if callable(spec):
        return [] if spec(value) else [path]
    if type(spec) is dict:
        if type(value) is not dict:
            return [path + ':type']
        errors = [path + ':keys'] if set(value) != set(spec) else []
        for key in spec:
            if key in value:
                errors += predicate_errors(value[key], spec[key], path + '.' + key)
        return errors
    if type(value) is not list:
        return [path + ':type']
    return [error for item in value for error in predicate_errors(item, spec[0], path + '[]')]


def word_text(path):
    ns = {'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
    with ZipFile(path) as package:
        names = [n for n in package.namelist() if n == 'word/document.xml' or
                 re.fullmatch(r'word/(header|footer)\d+\.xml', n) or
                 n in ('word/footnotes.xml', 'word/endnotes.xml', 'word/comments.xml')]
        return {name: [''.join(t.text or '' for t in p.findall('.//w:t', ns))
                        for p in ET.fromstring(package.read(name)).findall('.//w:p', ns)]
                for name in names}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--project', type=Path, required=True)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=False)
    source_files = sorted(p for p in args.source.iterdir() if p.is_file())
    engine_dir = args.project / 'data-template/task-v3'
    engine_files = [engine_dir / 'bundle_engine.py', *sorted((engine_dir / 'core').glob('*.*'))]
    doc_path = args.project / 'dataset/AI.docx'
    protected_files = source_files + engine_files + [doc_path]
    before = {str(p): sha(p) for p in protected_files}
    jsons = {p.name: load_json(p) for p in source_files if p.suffix.lower() == '.json'}
    csvs = {p.name: read_csv(p) for p in source_files if p.suffix.lower() == '.csv'}
    # Read every delivered text/code file completely, never execute attachments as instructions.
    texts = {p.name: p.read_text(encoding='utf-8-sig') for p in source_files
             if p.suffix.lower() in ('.md', '.py', '.txt')}
    doc_contents = word_text(doc_path)
    (args.output / 'attachment6_extracted.txt').write_text(
        '\n'.join(name + '\n' + '\n'.join(parts) for name, parts in doc_contents.items()), encoding='utf-8')
    base = jsons['source_expanded_v3.json']
    extension = jsons['source_extension_v4_candidate.json']
    seed = jsons['seed_source_v3.json']
    envs = base['phone_environments']
    tasks = base['task_requests'] + extension['task_requests']
    env_map = {e['environment_id']: e for e in envs}
    relation_errors = []
    def require(ok, code, rid):
        if not ok:
            relation_errors.append({'code': code, 'record_id': rid})
    require(len(env_map) == len(envs), 'duplicate_environment_id', 'dataset')
    require(len({t['task_id'] for t in tasks}) == len(tasks), 'duplicate_task_id', 'dataset')
    for env in envs:
        eid = env['environment_id']
        state, init = env['continuous_state_data'], env['initialization_data']
        captured = env['captured_offset_sec']
        indexes = {}
        for bucket, key in [('contacts', 'contact_id'), ('installed_apps', 'app_id'),
                            ('photos', 'photo_id'), ('messages', 'message_id'),
                            ('notifications', 'notification_id'), ('calendar', 'event_id')]:
            records = init[bucket] if bucket in init else state[bucket]
            indexes[bucket] = {r[key]: r for r in records}
            require(len(indexes[bucket]) == len(records), 'duplicate_' + key, eid)
        for photo in state['photos']:
            require(photo['taken_offset_sec'] <= captured, 'future_photo', eid)
            require(photo['pixels_available'] is False, 'real_pixels', eid)
        for message in state['messages']:
            require(message['received_offset_sec'] <= captured, 'future_message', eid)
            urls = re.findall(r'https?://([^/\s]+)', message['text'])
            require(all(u.endswith('.example.invalid') for u in urls), 'nonfictional_url', eid)
        for notification in state['notifications']:
            require(notification['app_id'] in indexes['installed_apps'], 'notification_app_missing', eid)
            require(notification['message_id'] in indexes['messages'], 'notification_message_missing', eid)
            if notification['message_id'] in indexes['messages']:
                require(indexes['messages'][notification['message_id']]['received_offset_sec'] <=
                        notification['post_offset_sec'] <= captured, 'notification_time_invalid', eid)
        require(state['screen']['current_app_id'] is None or
                state['screen']['current_app_id'] in indexes['installed_apps'], 'screen_reference_invalid', eid)
        require(state['location']['latitude'] == 0 and state['location']['longitude'] == 0,
                'location_not_placeholder', eid)
        system = state['system']
        require(system['ring_volume_current'] is None or system['ring_volume_max'] is None or
                system['ring_volume_current'] <= system['ring_volume_max'], 'volume_out_of_range', eid)
        network = state['network']
        require(network['connected'] is not False or (network['transport'] in ('NONE', 'UNKNOWN') and
                network['internet_validated'] is not True), 'network_conflict', eid)
        require(network['transport'] != 'NONE' or (network['connected'] is not True and
                network['internet_validated'] is not True), 'network_transport_conflict', eid)
    for task in tasks:
        tid = task['task_id']
        require(task['environment_id'] in env_map, 'task_environment_missing', tid)
        if task['environment_id'] not in env_map:
            continue
        env = env_map[task['environment_id']]
        require(task['user_id'] == env['initialization_data']['user_profile']['user_id'], 'task_user_mismatch', tid)
        require(task['requested_offset_sec'] >= env['captured_offset_sec'], 'task_before_snapshot', tid)
        for param, bucket, field in [('photo_id', 'photos', 'photo_id'), ('message_id', 'messages', 'message_id'),
                                    ('notification_id', 'notifications', 'notification_id')]:
            ref = task['parameters'].get(param)
            require(ref is None or ref in {r[field] for r in env['continuous_state_data'][bucket]},
                    'selected_' + param + '_invalid', tid)
        require(task['privacy_mode'] in ('STRICT_LOCAL', 'MINIMIZED_CLOUD'), 'unknown_privacy_mode', tid)
    csv_comparisons = {}
    for filename, records in [('phone_environments_expanded.csv', envs),
                              ('task_requests_expanded.csv', base['task_requests']),
                              ('task_requests_extension_v4_candidate.csv', extension['task_requests']),
                              ('seed_phone_environments.csv', seed['phone_environments']),
                              ('seed_task_requests.csv', seed['task_requests'])]:
        csv_comparisons[filename] = compare_csv(args.source / filename, records)
    sys.path.insert(0, str(engine_dir))
    engine = importlib.import_module('bundle_engine')
    env_errors = {e['environment_id']: predicate_errors(e, engine.ENV_SPEC) for e in envs}
    task_errors = {}
    for task in base['task_requests']:
        task_errors[task['task_id']] = (predicate_errors(task, engine.TASK_SPEC) +
            engine.v2.validate_task(engine.adapt(task, env_map[task['environment_id']])))
    try:
        engine.validate(base)
        whole_source_v3 = {'passed': True}
    except ValueError as exc:
        whole_source_v3 = {'passed': False, 'error_code': str(exc)}
    joint_eligible = [t['task_id'] for t in base['task_requests']
                      if not task_errors[t['task_id']] and not env_errors[t['environment_id']]]
    sdk_releases = {29: '10', 30: '11', 31: '12', 32: '12', 33: '13', 34: '14', 35: '15', 36: '16'}
    android_errors = [{'environment_id': e['environment_id'], 'sdk_int': e['initialization_data']['device']['sdk_int'],
                       'reported': e['initialization_data']['device']['android_release'],
                       'expected': sdk_releases[e['initialization_data']['device']['sdk_int']]}
                      for e in envs if e['initialization_data']['device']['android_release'] !=
                      sdk_releases[e['initialization_data']['device']['sdk_int']]]
    semantics = []
    for task in tasks:
        params, text, scenario = task['parameters'], task['utterance'], task['scenario_id']
        def semantic_issue(code, observed, expected):
            semantics.append({'task_id': task['task_id'], 'scenario_id': scenario, 'code': code,
                              'utterance': text, 'parameter_value': observed, 'expected_from_explicit_word': expected})
        if scenario in ('SHARE_PHOTO', 'CALL_CONTACT', 'SEND_MESSAGE'):
            for word, relation in [('女兒', 'daughter'), ('兒子', 'son'), ('朋友', 'friend'),
                                    ('孫女', 'granddaughter'), ('孫子', 'grandson'), ('鄰居', 'neighbor')]:
                if word in text and params.get('recipient_relation') != relation:
                    semantic_issue('explicit_recipient_conflict', params.get('recipient_relation'), relation)
        if scenario == 'OPEN_APP':
            for word, app in [('LINE', 'LINE'), ('相簿', 'GALLERY'), ('設定', 'SETTINGS'), ('地圖', 'MAPS')]:
                if word in text and params.get('app_name') != app:
                    semantic_issue('explicit_app_conflict', params.get('app_name'), app)
        if scenario == 'SET_REMINDER':
            for word, kind in [('吃藥', 'medicine'), ('繳費', 'bill'), ('回診', 'checkup')]:
                if word in text and params.get('reminder_kind') != kind:
                    semantic_issue('explicit_reminder_kind_conflict', params.get('reminder_kind'), kind)
        if scenario == 'SEND_MESSAGE' and '明天' in text and '今晚' in params.get('message_text', ''):
            semantic_issue('explicit_message_day_conflict', params['message_text'], 'tomorrow, not tonight')
    baseline_path = engine_dir / 'examples/results/derived_tasks.csv'
    baseline_equal = (args.source / 'baseline_derived_tasks_v3_3rows.csv').read_bytes() == baseline_path.read_bytes()
    # Generator executes in an isolated new folder; never import/run it inside the submitted dataset.
    repro = args.output / 'generator_reproduction'
    repro.mkdir()
    for name in ['generate_raw_dataset.py', 'seed_source_v3.json', 'seed_phone_environments.csv', 'seed_task_requests.csv']:
        shutil.copy2(args.source / name, repro / name)
    run = subprocess.run([sys.executable, '-B', str(repro / 'generate_raw_dataset.py')],
                         cwd=str(repro), capture_output=True, text=True, encoding='utf-8',
                         env={**os.environ, 'PYTHONUTF8': '1'})
    (args.output / 'generator_run.log').write_text(run.stdout + run.stderr, encoding='utf-8')
    reproduction = {p.name: {'matched_sha256': sha(p) == sha(repro / p.name)}
                    for p in source_files if (repro / p.name).exists() and p.name not in
                    ('generate_raw_dataset.py', 'seed_source_v3.json', 'seed_phone_environments.csv', 'seed_task_requests.csv')}
    new_counts = {'phone_environments': len(envs), 'tasks_v3_compatible': len(base['task_requests']),
                  'tasks_extension_only': len(extension['task_requests']), 'total_task_requests': len(tasks),
                  'scenarios_v3': dict(Counter(t['scenario_id'] for t in base['task_requests'])),
                  'scenarios_extension_only': dict(Counter(t['scenario_id'] for t in extension['task_requests'])),
                  'privacy_modes': dict(Counter(t['privacy_mode'] for t in tasks)),
                  'confirmation': dict(Counter(t['confirmation'] for t in tasks)),
                  'cancelled': sum(t['cancelled'] for t in tasks),
                  'base_missing_photo': sum(t['scenario_id'] == 'SHARE_PHOTO' and t['parameters'].get('photo_id') is None for t in base['task_requests']),
                  'base_missing_message': sum(t['scenario_id'] == 'CHECK_MESSAGE' and t['parameters'].get('message_id') is None for t in base['task_requests'])}
    claimed = jsons['quality_report.json']
    after = {str(p): sha(p) for p in protected_files}
    report = {
        'audit_version': '1.0', 'scope': 'READ_ONLY_INTAKE_NOT_PRIVACY_PROCESSING',
        'dataset_source': str(args.source), 'engine_found': str(engine_dir / 'bundle_engine.py'),
        'handoff_found_in_dataset': (args.source / 'HANDOFF.md').exists(),
        'all_files_read': [p.name for p in source_files],
        'json_documents_parsed': len(jsons), 'csv_documents_parsed': len(csvs),
        'word_document_read': str(doc_path),
        'word_paragraph_count_including_tables': sum(len(v) for v in doc_contents.values()),
        'counts': new_counts,
        'quality_report_counts_match': all(claimed[k] == v for k, v in new_counts.items()),
        'duplicated_environment_arrays_equal': extension['phone_environments'] == envs,
        'csv_json_comparisons': csv_comparisons,
        'reference_time_placeholder_errors': relation_errors,
        'original_seed_environments_preserved': envs[:len(seed['phone_environments'])] == seed['phone_environments'],
        'original_seed_tasks_preserved': base['task_requests'][:len(seed['task_requests'])] == seed['task_requests'],
        'baseline_derived_rows': len(csvs['baseline_derived_tasks_v3_3rows.csv'][1]),
        'baseline_byte_identical_to_existing_v3': baseline_equal,
        'source_provenance': base['provenance'], 'old_v3_required_provenance': 'DESIGNED_SYNTHETIC_NO_REAL_DEVICE_VALUES',
        'v3_whole_source_validation': whole_source_v3,
        'v3_environment_contract_valid_count': sum(not v for v in env_errors.values()),
        'v3_environment_errors_by_path': dict(Counter(p for v in env_errors.values() for p in set(v))),
        'v3_task_contract_valid_count_ignoring_environment': sum(not v for v in task_errors.values()),
        'v3_task_errors_by_path': dict(Counter(p for v in task_errors.values() for p in set(v))),
        'v3_joint_task_environment_contract_valid_ids': joint_eligible,
        'v3_errors_per_environment': env_errors, 'v3_errors_per_task': task_errors,
        'android_release_mismatches': android_errors,
        'android_mapping_source': 'https://developer.android.com/guide/topics/manifest/uses-sdk-element#ApiLevels',
        'age_band_counts': dict(Counter(e['initialization_data']['user_profile']['age_band'] for e in envs)),
        'sdk_counts': dict(Counter(e['initialization_data']['device']['sdk_int'] for e in envs)),
        'unique_message_texts': len({m['text'] for e in envs for m in e['continuous_state_data']['messages']}),
        'selected_message_texts': len({m['text'] for t in base['task_requests'] if t['scenario_id'] == 'CHECK_MESSAGE'
                                      for m in env_map[t['environment_id']]['continuous_state_data']['messages']
                                      if m['message_id'] == t['parameters'].get('message_id')}),
        'semantic_checks_are_heuristics_not_ground_truth': True,
        'semantic_conflict_unique_tasks': len({s['task_id'] for s in semantics}),
        'semantic_conflict_counts': dict(Counter(s['code'] for s in semantics)),
        'semantic_conflicts': semantics,
        'extension_grants_patterns': dict(Counter('|'.join(t['grants']) for t in extension['task_requests'])),
        'extension_cloud_features_granted': sum('cloud_features' in t['grants'] for t in extension['task_requests']),
        'navigation_tasks_without_destination_catalog': sum(t['scenario_id'] == 'NAVIGATE_TO_PLACE' for t in tasks),
        'generator_return_code': run.returncode, 'generator_reproduction': reproduction,
        'source_files_unchanged': before == after, 'source_manifest_sha256': before,
        'limitations': ['No new derived tasks generated; 576 tasks remain raw synthetic data.',
                       'No genuine phone execution, Gemini API call or privacy/fidelity/utility experiment performed.',
                       'Semantic word checks identify contradictions, not a complete NLU correctness assessment.',
                       '72 synthetic environments do not represent 72 measured older adults.',
                       'Fixture-only old engine rejects this new raw bundle; scenario-name support is not compatibility.']}
    (args.output / 'audit_report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (args.output / 'input_manifest.json').write_text(json.dumps(before, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    summary = {k: report[k] for k in ['counts', 'quality_report_counts_match', 'csv_json_comparisons',
               'reference_time_placeholder_errors', 'baseline_byte_identical_to_existing_v3',
               'v3_whole_source_validation', 'v3_environment_contract_valid_count',
               'v3_task_contract_valid_count_ignoring_environment', 'v3_joint_task_environment_contract_valid_ids',
               'age_band_counts', 'semantic_conflict_unique_tasks', 'semantic_conflict_counts',
               'extension_grants_patterns', 'generator_return_code', 'generator_reproduction', 'source_files_unchanged']}
    summary['android_release_mismatch_count'] = len(android_errors)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
