"""AI-authored fictional scenario bank; source design, not a PETs algorithm."""
import json
from pathlib import Path

SCENES = {
 'SHARE_PHOTO': [
  ('selected', 'selected', ['幫我把這張照片傳給{recipient}', '把我選的照片用 LINE 分享給{recipient}', '這張相片，麻煩傳給{recipient}', '我想把這張照片送給{recipient}']),
  ('latest', 'latest', ['剛拍的照片傳給{recipient}', '把最新那張照片分享給{recipient}', '請把最近拍的一張照片給{recipient}', '用 LINE 把最後拍的相片傳給{recipient}']),
  ('family_album', 'family_album', ['把家庭相簿選的照片給{recipient}', '家庭相簿這張，用 LINE 分享給{recipient}', '我要把家庭相簿的這張傳給{recipient}', '麻煩把家人相簿選的相片給{recipient}']),
  ('outing_album', 'outing_album', ['把出遊相簿選的照片給{recipient}', '這張出遊照片分享給{recipient}', '我想傳出遊相簿這張給{recipient}', '用 LINE 把出遊相簿選的相片給{recipient}']),
  ('location_metadata', 'selected', ['這張有位置資訊的照片傳给{recipient}', '我想把這張帶定位的照片給{recipient}', '幫我分享這張有拍攝位置的照片給{recipient}', '把有定位資訊的這張相片傳給{recipient}']),
  ('private_background', 'selected', ['這張背景有私人東西的照片想傳給{recipient}', '把這張背景有個人物品的照片給{recipient}', '這張照片背景有私人資料，我想分享給{recipient}', '我想把背景有私人東西的這張相片給{recipient}']),
  ('limited_selection', 'selected', ['只把我選的這一張傳給{recipient}', '不要整本相簿，只分享這張給{recipient}', '用 LINE 傳這一張給{recipient}就好', '僅分享選中的照片給{recipient}']),
  ('reconfirm_target', 'selected', ['傳照片前先確認是{recipient}', '這張給{recipient}，送出前再問我', '想傳這張給{recipient}，先讓我確認', '用 LINE 把這張給{recipient}，傳之前問一下'])],
 'CHECK_MESSAGE': [
  ('general_risk', 'selected', ['這一則訊息是不是騙人的', '幫我看看這則訊息有沒有風險', '這個訊息能相信嗎', '請檢查我選的訊息']),
  ('sender_unknown', 'selected', ['這個陌生人傳的訊息可信嗎', '不認識的人傳來這則，幫我看看', '陌生訊息我該怎麼辦', '幫我看看陌生來源的這一則']),
  ('link_review', 'selected', ['這則有連結的訊息能點嗎', '幫我看這個連結訊息有沒有問題', '這一則裡有網址，我該點嗎', '選的訊息有連結，請幫我檢查']),
  ('money_request', 'selected', ['這則叫我付款的訊息可靠嗎', '有人傳這則要錢，請幫我看看', '這一則要求轉帳，是不是有問題', '選的這個付款要求可信嗎']),
  ('otp_request', 'selected', ['這則要驗證碼的訊息正常嗎', '有人在這則訊息問驗證碼，怎麼辦', '對方叫我提供驗證碼，幫我看看', '選的訊息要一次性驗證碼，可信嗎']),
  ('official_impersonation', 'selected', ['這則自稱官方的訊息可信嗎', '他說是官方窗口，這則有問題嗎', '幫我查這則自稱官方的訊息', '這則說是機構通知，我該相信嗎']),
  ('family_impersonation', 'selected', ['這則說是家人的訊息可靠嗎', '有人在這則說是親友，幫我看', '自稱我家人的這則可信嗎', '幫我判斷這則親友聯絡訊息']),
  ('benign_or_uncertain', 'selected', ['這個生活通知有問題嗎', '幫我看看這則日常提醒', '這一則普通通知能相信嗎', '請檢查這個生活訊息'])],
 'RESCUE_PHONE': [
  ('no_sound', 'sound', ['手機突然沒聲音，幫我看看', '通知怎麼都不響了', '我聽不到鈴聲，手機壞了嗎', '手機沒有聲音，能幫我檢查嗎']),
  ('low_volume', 'sound', ['手機聲音太小，幫我看看', '鈴聲小到聽不到，怎麼辦', '通知聲好小，請幫忙檢查', '我覺得鈴聲變很小']),
  ('dnd', 'sound', ['是不是勿擾模式讓手機沒聲音', '幫我看看有沒有開到勿擾', '我不會關勿擾，先幫我檢查', '通知不響，可能是勿擾嗎']),
  ('airplane', 'connectivity', ['手機是不是不小心開了飛航模式', '幫我看飛航模式有沒有開', '我好像碰到飛航，請檢查', '不能連線，是飛航模式嗎']),
  ('offline', 'connectivity', ['手機沒有網路，幫我看看', '網路不能用了，該怎麼辦', '這支手機現在連不上網', '我不知道為什麼手機離線']),
  ('wifi', 'connectivity', ['Wi-Fi 連不上，請幫我檢查', '手機無線網路怪怪的', '幫我看現在 Wi-Fi 怎麼了', '我家的 Wi-Fi 好像不能用了']),
  ('unknown_issue', 'general', ['手機好像壞掉了，幫我看', '我不知道按了什麼，手機怪怪的', '這支手機不太對勁，能檢查嗎', '幫我看看手機目前的設定']),
  ('check_only', 'general', ['只檢查手機狀態，先不要改設定', '先告訴我手機怎麼了，不要直接改', '幫我看設定，變更之前先問我', '請檢查手機，但暫時不要動設定'])],
 'CALL_CONTACT': [
  ('normal', 'ordinary', ['幫我打電話給{recipient}', '我要撥給{recipient}', '請幫我聯絡{recipient}，用電話', '麻煩打給{recipient}']),
  ('reconfirm', 'confirm_first', ['打給{recipient}前先讓我確認', '我要撥給{recipient}，先問我再打', '先確認是{recipient}再打電話', '打電話給{recipient}，不要立刻撥出']),
  ('callback', 'ordinary', ['幫我回電給{recipient}', '我要打回去給{recipient}', '請幫我撥電話回給{recipient}', '用電話回覆{recipient}']),
  ('check_number', 'confirm_first', ['幫我找{recipient}的電話，先別打', '查一下{recipient}的電話，讓我確認', '我要打給{recipient}，先確認號碼', '先找到{recipient}，不要馬上撥號']),
  ('audio_only', 'ordinary', ['用一般電話聯絡{recipient}，不是視訊', '打語音電話給{recipient}就好', '請用電話撥給{recipient}，不要視訊', '我要跟{recipient}通一般電話']),
  ('urgent_family', 'ordinary', ['我有事找{recipient}，幫我打電話', '請快幫我撥給{recipient}', '想趕快聯絡{recipient}，用電話', '有件事要問{recipient}，請幫我打電話']),
  ('no_cloud', 'local_only', ['在手機上打給{recipient}，不要送資料到雲端', '用本機電話聯絡{recipient}就好', '幫我打給{recipient}，資料留手機', '只在手機上找{recipient}並撥號']),
  ('cancel_followup', 'ordinary', ['幫我準備打給{recipient}', '我要撥給{recipient}，先準備一下', '幫我準備{recipient}的電話', '打给{recipient}的電話先準備好'])],
 'SEND_MESSAGE': [
  ('arrived', '我已經到了。', ['跟{recipient}說我已經到了', '傳訊息給{recipient}：我已經到了', '幫我告訴{recipient}，我到了', '用 LINE 跟{recipient}說我已經到了']),
  ('late', '我會晚一點回家。', ['跟{recipient}說我會晚一點回家', '傳給{recipient}：我會晚一點回家', '告訴{recipient}我晚點回家', '幫我用 LINE 說給{recipient}，我晚點回家']),
  ('umbrella', '請記得帶雨傘。', ['跟{recipient}說請記得帶雨傘', '告訴{recipient}要記得帶雨傘', '傳訊息給{recipient}：請記得帶雨傘', '幫我提醒{recipient}記得帶雨傘']),
  ('tomorrow_meet', '明天下午一起見面，好嗎？', ['傳訊息約{recipient}明天下午見面', '問{recipient}明天下午能不能見面', '用 LINE 跟{recipient}約明天下午', '幫我問{recipient}明天下午一起見面好嗎']),
  ('thanks', '謝謝你的幫忙。', ['跟{recipient}說謝謝你的幫忙', '傳給{recipient}：謝謝你的幫忙', '幫我謝謝{recipient}的幫忙', '用 LINE 跟{recipient}說聲謝謝幫忙']),
  ('tonight_home', '我今晚七點會回家。', ['跟{recipient}說我今晚七點會回家', '告訴{recipient}今晚七點我回家', '用 LINE 傳給{recipient}：我今晚七點回家', '幫我通知{recipient}，我今晚七點會回家']),
  ('call_later', '請晚一點打電話給我。', ['跟{recipient}說請晚點打給我', '傳給{recipient}：晚一點打電話給我', '告訴{recipient}稍晚再打電話給我', '用 LINE 請{recipient}晚點打電話給我']),
  ('clinic_time', '我明天下午兩點要回診。', ['告訴{recipient}我明天下午兩點要回診', '傳給{recipient}：明天下午兩點我回診', '跟{recipient}說我明天下午兩點要去回診', '用 LINE 通知{recipient}，我明天下午兩點回診'])],
 'READ_NOTIFICATION': [
  ('latest', 'latest', ['幫我念最新的一則通知', '讀出最後收到的通知', '最新跳出來的是什麼，念給我聽', '請讀最新那個通知']),
  ('selected', 'selected', ['幫我念選中的通知', '這個通知請念給我聽', '讀出我選的這一則', '請念畫面上選定的通知']),
  ('messages', 'MESSAGES', ['幫我念簡訊 App 最新的通知', '讀出簡訊 App 最近的提醒', '簡訊 App 新通知說什麼', '請念簡訊 App 最新那一則']),
  ('line', 'LINE', ['幫我念 LINE 最新通知', 'LINE 最近跳出的通知說什麼', '請读 LINE 新收到的通知', '把 LINE 最新的提醒念給我聽']),
  ('calendar', 'CALENDAR', ['幫我念行事曆的最新提醒', '行事曆最近通知什麼，念給我聽', '請讀行事曆最新通知', '把行事曆的最近提醒念出來']),
  ('selected_private', 'selected', ['這則私人通知念給我聽就好', '只念選的通知，不要分享出去', '幫我在手機上讀這個私人通知', '選中這一則，念給我聽但別轉傳']),
  ('latest_no_reply', 'latest', ['念最新通知，先不要回覆', '最新一則讀給我聽，不要幫我回', '我只想聽最新通知的內容', '讀最後的通知，暫時不回覆']),
  ('selected_sender', 'selected', ['告訴我選的通知來源和內容', '念出選定通知是誰傳來及內容', '這則通知的來源和內容請念出來', '幫我讀這則通知，包含來源'])],
 'SET_REMINDER': [
  ('medicine_tonight', ['medicine', 0, 20, 0], ['今天晚上八點提醒我吃藥', '幫我設今晚八點吃藥提醒', '我今天晚上八點要吃藥，提醒我', '設今天二十點的吃藥提醒']),
  ('checkup_tomorrow', ['checkup', 1, 14, 0], ['明天下午兩點提醒我回診', '幫我設明天下午兩點回診提醒', '我明天十四點要回診，提醒我', '設明天下午兩點去回診的提醒']),
  ('bill_nextweek', ['bill', 7, 9, 0], ['七天後早上九點提醒我繳費', '幫我設一週後早上九點的繳費提醒', '下週同一天早上九點我要繳費，提醒我', '設七天後九點繳費的提醒']),
  ('family_tomorrow', ['family_event', 1, 18, 30], ['明天晚上六點半提醒我家庭聚餐', '幫我設明天十八點三十分家庭聚餐提醒', '明晚六點半家人吃飯，請提醒我', '設明天晚上六點半的家庭聚餐提醒']),
  ('medicine_relative', ['medicine', 'relative', 1800], ['半小時後提醒我吃藥', '三十分鐘後幫我設吃藥提醒', '過半小時叫我吃藥', '幫我提醒，三十分鐘後要吃藥']),
  ('callback_relative', ['callback', 'relative', 3600], ['一小時後提醒我回電話', '六十分鐘後提醒我回電', '過一小時叫我回電話', '幫我设六十分鐘後回電提醒']),
  ('activity_tomorrow', ['activity', 1, 9, 0], ['明天早上九點提醒我參加活動', '幫我設明天上午九點的活動提醒', '明天九點要參加活動，請提醒我', '設明早九點參加活動的提醒']),
  ('shopping_tomorrow', ['shopping', 1, 10, 0], ['明天早上十點提醒我買東西', '幫我設明天上午十點購物提醒', '明早十點我要買東西，提醒我', '設明天十點去購物的提醒'])],
 'NAVIGATE_TO_PLACE': [
  ('hospital', 'hospital', ['帶我去已儲存的醫院', '幫我導航到我存的醫院', '我想去收藏的醫院，幫我導航', '請用地圖帶我去已儲存醫院']),
  ('station', 'station', ['導航到已儲存的火車站', '帶我去我收藏的車站', '請用地圖前往存好的火車站', '我想去已儲存車站，幫我帶路']),
  ('friend_home', 'friend_home', ['導航到已儲存的朋友家', '帶我去我收藏的朋友家', '請用地圖前往存好的朋友家', '我想去已儲存的朋友家']),
  ('park', 'park', ['導航到已儲存的公園', '帶我去我收藏的公園', '請用地圖帶路到存好的公園', '我想去已儲存公園，幫我導航']),
  ('home', 'home', ['幫我導航回已儲存的家', '帶我回收藏的住家', '請用地圖前往存好的家', '導航回我已儲存的住處']),
  ('clinic', 'clinic', ['導航到已儲存的診所', '帶我去我收藏的診所', '用地圖前往存好的診所', '我想去已儲存診所，請帶路']),
  ('community', 'community', ['帶我去已儲存的活動中心', '導航到我收藏的社區中心', '請用地圖前往存好的活動中心', '我想去已儲存社區中心']),
  ('store', 'store', ['導航到已儲存的商店', '帶我去我收藏的商店', '用地圖前往存好的商店', '我想去已儲存商店，幫我带路'])],
 'OPEN_APP': [
  ('line', 'LINE', ['幫我打開 LINE', '我要開 LINE', '請啟動 LINE', '把 LINE 開給我']),
  ('gallery', 'GALLERY', ['幫我打開相簿', '我要看相簿', '請啟動相簿', '把相簿開給我']),
  ('maps', 'MAPS', ['幫我打開地圖', '我要開地圖', '請啟動地圖 App', '把地圖開給我']),
  ('settings', 'SETTINGS', ['幫我打開設定', '我要看手機設定', '請啟動設定', '把設定畫面開給我']),
  ('calendar', 'CALENDAR', ['幫我打開行事曆', '我要看行事曆', '請啟動行事曆', '把行事曆開給我']),
  ('messages', 'MESSAGES', ['幫我打開簡訊 App', '我要看簡訊 App', '請啟動簡訊 App', '把簡訊 App 開給我']),
  ('phone', 'PHONE', ['幫我打開電話 App', '我要開電話 App', '請啟動電話 App', '把電話 App 開給我']),
  ('camera', 'CAMERA', ['幫我打開相機', '我要開相機', '請啟動相機 App', '把相機開給我'])]
}

# Semantics, not scam verdicts. Every identifier and hostname below is fictional.
MESSAGES = [
 ('family_meal', 'family', '明天下午一起吃飯。', []),
 ('community_maintenance', 'community', '明天有例行設備檢查，請留意公告。', []),
 ('courier_delivery', 'courier', '包裹已送達管理室，不需要付款或提供資料。', []),
 ('medical_schedule', 'clinic', '回診預約時間有調整，請透過原有官方窗口確認。', []),
 ('school_activity', 'school', '明天校園開放活動，歡迎參加。', []),
 ('friend_walk', 'friend', '週末一起去散步好嗎？', []),
 ('bill_official_app', 'bill', '帳單即將到期，請由原本使用的帳務 App 查詢。', []),
 ('booking_confirmation', 'service', '請確認預約資訊，可由原有官方 App 查看。', []),
 ('family_video', 'family', '今晚七點方便視訊嗎？', []),
 ('pharmacy_ready', 'pharmacy', '您預約的物品已準備好，請聯絡原有窗口確認。', []),
 ('event_change', 'community', '活動改到下午兩點，請注意時間。', []),
 ('friend_thanks', 'friend', '謝謝分享照片，改天再見。', []),
 ('urgent_transfer', 'unknown', '請立即轉帳到 SYN_ACCOUNT_PRIVATE_{i}，否則會停權。', ['transfer', 'urgency']),
 ('otp_support', 'unknown', '我是客服，請提供一次性驗證碼 SYN_OTP_PRIVATE_{i}。', ['otp', 'impersonation']),
 ('police_claim', 'unknown', '我是警察，馬上提供驗證碼才能解除限制。', ['otp', 'urgency', 'impersonation']),
 ('bank_link', 'unknown', '帳戶需要驗證，請到 https://bank.example.invalid/login 輸入資料。', ['link', 'credential']),
 ('reward_link', 'unknown', '中獎獎金待領取，立即點擊 https://reward.example.invalid/redeem。', ['link', 'urgency']),
 ('courier_fee', 'unknown', '包裹待領，請到 https://delivery.example.invalid/fee 支付費用。', ['link', 'payment']),
 ('family_money', 'unknown', '我是家人，臨時換號碼，請轉帳到 SYN_ACCOUNT_PRIVATE_{i}。', ['transfer', 'family_claim']),
 ('remote_support', 'unknown', '我是客服，請安裝遠端控制軟體並提供控制碼。', ['remote_control', 'impersonation']),
 ('password_request', 'unknown', '請把密碼 SYN_PASSWORD_PRIVATE_{i} 寄給我才能完成驗證。', ['credential']),
 ('investment_offer', 'unknown', '保證獲利的投資，需要先匯款到 SYN_ACCOUNT_PRIVATE_{i}。', ['payment', 'investment']),
 ('authority_payment', 'unknown', '我是機構人員，要求立刻付款才能避免處分。', ['payment', 'urgency', 'impersonation']),
 ('delivery_link_only', 'courier', '物流資訊可見 https://tracking.example.invalid/status，請自行核對来源。', ['link']),
 ('otp_do_not_share', 'service', '一次性驗證碼 SYN_OTP_PRIVATE_{i}，請勿告訴任何人。', ['otp_do_not_share']),
 ('account_warning', 'service', '若不是您登入，請使用原本的官方 App 更改密碼。', ['account_warning']),
 ('medical_private', 'clinic', 'SYN_NAME_PRIVATE_{i} 您的病歷號 SYN_RECORD_PRIVATE_{i}：回診預約已更新。', ['medical']),
 ('address_private', 'friend', '集合地點是 SYN_ADDRESS_PRIVATE_{i}，明天下午見。', ['address']),
 ('phone_private', 'family', '有需要請打 SYN_PHONE_PRIVATE_{i} 聯絡我。', ['phone']),
 ('identity_private', 'service', '預約資料：SYN_NAME_PRIVATE_{i}，識別碼 SYN_ID_PRIVATE_{i}。', ['identity']),
 ('fragment', 'unknown', '請處理這件事，細節還沒有說明。', ['insufficient_context']),
 ('unrelated_ad', 'advertisement', '一般商品宣傳，無需輸入個資。', [])
]


def catalog():
    return {
      'version': '5.0-raw', 'synthetic': True,
      'authorship': 'ChatGPT/Codex authored Chinese scenario and message template banks; deterministic offline Python instantiation. No per-row LLM/API call.',
      'not_privacy_processed': True,
      'scenario_templates': {sc: [{'template_id': sc + '_' + name, 'subscenario': name,
                                   'semantic_value': value,
                                   'utterance_patterns': [p.replace('给', '給').replace('读', '讀').replace('设', '設').replace('带', '帶') for p in patterns]}
                                  for name, value, patterns in items] for sc, items in SCENES.items()},
      'message_templates': [{'template_id': name, 'source_category': source,
                             'semantic_tags': tags,
                             'text_patterns': ['虛構' + source + '通知：' + body.replace('来源', '來源'),
                                               '這是虛構訊息。' + body.replace('来源', '來源'),
                                               '虛構測試內容：' + body.replace('来源', '來源')]}
                            for name, source, body, tags in MESSAGES],
      'message_tags_are_not_scam_verdicts': True,
      'known_design_limits': ['Finite authored template bank, not independent LLM generation for every row.',
                             'Fictional source only, not distribution-fit synthesis of measured people.',
                             'Phrase variants from one family are correlated; split evaluation by environment AND template family.']}


if __name__ == '__main__':
    target = Path(__file__).resolve().parent / 'template_catalog.json'
    with target.open('x', encoding='utf-8', newline='\n') as handle:
        json.dump(catalog(), handle, ensure_ascii=False, indent=2)
        handle.write('\n')
