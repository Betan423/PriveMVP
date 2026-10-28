"""Engineering tests of raw-source validation; no privacy/agent success claims."""
import copy
import json
import unittest
from pathlib import Path
from validate_raw_dataset import validate, pairs, reject_constant

BASE=Path(__file__).resolve().parent


class RawTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.original=json.loads((BASE/'dataset/source_raw_v5.json').read_text(encoding='utf-8'))
        cls.catalog=json.loads((BASE/'template_catalog.json').read_text(encoding='utf-8'))
    def setUp(self):
        self.source=copy.deepcopy(self.original)
        self.env=self.source['phone_environments'][0]
    def task(self,sc):
        return next(t for t in self.source['task_requests'] if t['scenario_id']==sc)
    def invalid(self):
        self.assertFalse(validate(self.source,self.catalog)['passed'])
    def test_valid_full_dataset(self):
        self.assertTrue(validate(self.source,self.catalog)['passed'])
    def test_duplicate_environment(self):
        self.source['phone_environments'].append(copy.deepcopy(self.env));self.invalid()
    def test_duplicate_task(self):
        self.source['task_requests'].append(copy.deepcopy(self.source['task_requests'][0]));self.invalid()
    def test_wrong_android_version(self):
        self.env['initialization_data']['device']['android_release']='99';self.invalid()
    def test_boolean_timestamp(self):
        self.env['captured_offset_sec']=True;self.invalid()
    def test_real_coordinates(self):
        self.env['continuous_state_data']['location']['latitude']=25;self.invalid()
    def test_real_phone_reference(self):
        self.env['initialization_data']['contacts'][0]['phone_ref']='0912345678';self.invalid()
    def test_photo_pixels(self):
        self.env['continuous_state_data']['photos'][0]['pixels_available']=True;self.invalid()
    def test_future_photo(self):
        self.env['continuous_state_data']['photos'][0]['taken_offset_sec']=999999;self.invalid()
    def test_future_message(self):
        self.env['continuous_state_data']['messages'][0]['received_offset_sec']=999999;self.invalid()
    def test_arbitrary_real_message(self):
        self.env['continuous_state_data']['messages'][0]['text']='真人私訊';self.invalid()
    def test_notification_orphan(self):
        self.env['continuous_state_data']['notifications'][0]['message_id']='SYN_MISSING';self.invalid()
    def test_network_conflict(self):
        self.env['continuous_state_data']['network']['transport']='NONE';self.invalid()
    def test_invalid_volume(self):
        self.env['continuous_state_data']['system']['ring_volume_current']=999;self.invalid()
    def test_task_environment_orphan(self):
        self.task('OPEN_APP')['environment_id']='SYN_MISSING';self.invalid()
    def test_user_mismatch(self):
        self.task('OPEN_APP')['user_id']='SYN_WRONG';self.invalid()
    def test_utterance_param_mismatch(self):
        self.task('CALL_CONTACT')['parameters']['recipient_relation']='doctor';self.invalid()
    def test_app_semantics(self):
        self.task('OPEN_APP')['parameters']['app_name']='OTHER';self.invalid()
    def test_message_draft_semantics(self):
        self.task('SEND_MESSAGE')['parameters']['message_text']='明天改成今天。';self.invalid()
    def test_reminder_kind(self):
        self.task('SET_REMINDER')['parameters']['reminder_kind']='wrong';self.invalid()
    def test_reminder_time(self):
        self.task('SET_REMINDER')['parameters']['scheduled_at']='2000-01-01T00:00:00+08:00';self.invalid()
    def test_destination_orphan(self):
        self.task('NAVIGATE_TO_PLACE')['parameters']['destination_ref']='SYN_MISSING';self.invalid()
    def test_cloud_grant_strict_local(self):
        t=self.task('OPEN_APP');t['privacy_mode']='STRICT_LOCAL';t['grants'].append('cloud_features');self.invalid()
    def test_cancel_followup(self):
        t=self.task('OPEN_APP');t['cancelled']=True;t['followup_utterance']=None;self.invalid()
    def test_claim_privacy_processed(self):
        self.source['privacy_processing_applied']=True;self.invalid()
    def test_missing_required_parameter(self):
        del self.task('OPEN_APP')['parameters']['app_name'];self.invalid()
    def test_extra_raw_field(self):
        self.env['continuous_state_data']['unreviewed_raw_private_text']='secret';self.invalid()
    def test_parser_duplicate_key(self):
        with self.assertRaises(ValueError):json.loads('{"x":1,"x":2}',object_pairs_hook=pairs)
    def test_parser_nonfinite(self):
        with self.assertRaises(ValueError):json.loads('{"x":NaN}',parse_constant=reject_constant)


if __name__=='__main__':
    unittest.main(verbosity=2)
