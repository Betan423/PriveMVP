#!/usr/bin/env python3
# Deterministic raw synthetic data generator, no PET processing.
from pathlib import Path
import json, csv, random, copy, zipfile, collections, hashlib, os, shutil, re

OUT = Path(__file__).resolve().parent
OUT.mkdir(parents=True, exist_ok=True)
SEED = json.loads((OUT / "seed_source_v3.json").read_text(encoding="utf-8"))
R = random.Random(20261012)
PHRASES = {
"SHARE_PHOTO": ["幫我把這張照片傳給女兒", "剛拍的照片傳給家人", "這張照片分享給兒子", "可以幫我傳照片給朋友嗎", "我想把相簿裡選的照片傳出去", "幫我把照片傳給孫子"],
"CHECK_MESSAGE": ["這一則訊息是不是騙人的", "這個連結能點嗎", "有人說要我轉帳，幫我看看", "剛剛的通知有詐騙風險嗎", "這則訊息要不要相信", "幫我看一下收到的簡訊"],
"RESCUE_PHONE": ["手機怎麼突然沒聲音", "為什麼我的網路不能用了", "手機好像壞掉了", "怎麼都聽不到通知聲", "我按了手機怎麼沒網路", "幫我看看是不是誤按了設定"],
"CALL_CONTACT": ["幫我打電話給女兒", "幫我撥給兒子", "我要打給鄰居", "幫我打電話給朋友", "我想聯絡孫子"],
"SEND_MESSAGE": ["跟女兒說晚點回家", "告訴兒子我已經到了", "傳訊息跟朋友約明天下午", "幫我跟孫子說記得帶雨傘"],
"READ_NOTIFICATION": ["剛剛跳出來的是什麼通知", "幫我念最新一則通知", "是不是有新的訊息", "手機剛剛提醒什麼"],
"SET_REMINDER": ["提醒我明天下午回診", "今天晚上提醒我吃藥", "幫我設定明早九點提醒", "下週提醒我繳費"],
"NAVIGATE_TO_PLACE": ["帶我去醫院", "我想去火車站", "幫我導航到朋友家", "帶我去附近公園"],
"OPEN_APP": ["幫我打開 LINE", "我要看相簿", "開啟地圖", "幫我打開設定"],
}
MESSAGES = [
"虛構家人提醒：明天下午一起吃飯。",
"虛構社區通知：明日有例行設備檢查。",
"我是虛構客服，請立即轉帳並提供驗證碼，點選 https://notice.example.invalid/check",
"虛構親友說：週末天氣好，一起散步。",
"虛構銀行通知：您的帳戶需要驗證，請在 https://bank.example.invalid/login 輸入資料。",
"虛構醫院提醒：下週門診時間已調整，請聯絡官方窗口確認。",
"虛構陌生人說：我是警察，馬上提供一次性驗證碼才能解除限制。",
"虛構快遞提醒：包裹已送達管理室，無需提供個資。",
"虛構廣告說：中獎獎金待領取，請立即點擊 https://reward.example.invalid/redeem",
"虛構鄰居提醒：活動時間改成下午兩點。",
"虛構家人詢問：今晚七點是否方便視訊？",
"虛構假冒客服要求：請把密碼寄給我，以完成身分驗證。",
"虛構健檢提醒：請依照醫療院所官方通知辦理。",
"虛構訊息：請確認預約資訊，詳情請由官方 App 查看。",
"虛構朋友留言：謝謝你分享照片，改天見。",
"虛構促銷通知：限時優惠，不必輸入個人資料。",
"虛構帳務提醒：帳單即將到期，請循原有帳務 App 查詢。",
"虛構可疑訊息：若不馬上付款就會被停權。",
"虛構陌生訊息：嗨，可以跟我聯絡嗎？",
"虛構學校通知：明日校園開放活動。",
]
relations = ["daughter","son","granddaughter","grandson","friend","neighbor","caregiver","doctor"]
apps_base = [
("LINE","jp.naver.line.android"),
("GALLERY","com.example.synthetic.gallery"),
("SETTINGS","com.example.synthetic.settings"),
("MESSAGES","com.example.synthetic.messages"),
("MAPS","com.example.synthetic.maps"),
("CALENDAR","com.example.synthetic.calendar"),
("BROWSER","com.example.synthetic.browser"),
("PHONE","com.example.synthetic.phone"),
("CAMERA","com.example.synthetic.camera"),
]
ages=["65-69","70-74","75-79","80-84","85-89","90+"]
def pick(seq,i=0): return R.choice(seq)
def synth_environment(i):
    eid=f"SYN_ENV{i:04d}"; uid=f"SYN_U{i:03d}"; t=600+i*30
    # Intentionally contain zero-contact and multi-candidate states.
    ncontacts = [0,1,2,3,4,5,7,8][(i*3)%8]
    rs = [pick(relations) for _ in range(ncontacts)]
    if i % 7 == 0 and ncontacts>=2: rs[0]=rs[1]="daughter"
    contacts=[dict(contact_id=f"SYN_C{i:03d}_{j:03d}",display_name=f"SYN_CONTACT_{i:03d}_{j:03d}",
       relationship=r,phone_ref=f"SYN_PHONE_PRIVATE_{i:03d}_{j:03d}",line_ref=f"SYN_LINE_PRIVATE_{i:03d}_{j:03d}") for j,r in enumerate(rs,1)]
    approws=[dict(app_id=f"SYN_A{i:03d}_{j:03d}",name=name,package=package,installed=not (i%11==0 and name in ("LINE","GALLERY")))
             for j,(name,package) in enumerate(apps_base,1) if j<=3 or (i+j)%3!=0]
    appids=[a["app_id"] for a in approws if a["installed"]]
    npics= [0,1,2,3,5,7][i%6]
    photos=[dict(photo_id=f"SYN_P{i:03d}_{j:03d}", display_name=f"SYN_PHOTO_{i:03d}_{j:03d}.jpg",
      media_ref=f"SYN_MEDIA_PRIVATE_{i:03d}_{j:03d}", taken_offset_sec=max(0,t-R.randint(10,520)),
      mime_type="image/jpeg",size_bytes=R.randint(110,5000)*1024,width=R.choice([720,1080,1920,2560]),
      height=R.choice([720,1080,1920]),exif_location_ref=(f"SYN_LOCATION_PRIVATE_{i:03d}" if (j+i)%3==0 else None),
      pixels_available=False) for j in range(1,npics+1)]
    nm=[0,1,2,4,6][i%5]
    chosen_msgs=R.sample(MESSAGES,min(nm,len(MESSAGES)))
    messages=[dict(message_id=f"SYN_M{i:03d}_{j:03d}",sender_ref=f"SYN_SENDER_PRIVATE_{i:03d}_{j:03d}",
       text=m,received_offset_sec=max(0,t-10-j*15),context_sufficient=(j+i)%5!=0) for j,m in enumerate(chosen_msgs,1)]
    nots=[dict(notification_id=f"SYN_N{i:03d}_{j:03d}",app_id=appids[0] if appids else None,
       message_id=m["message_id"],post_offset_sec=m["received_offset_sec"]+1) for j,m in enumerate(messages[:3],1)]
    ncal=i%5
    calendar=[dict(event_id=f"SYN_CAL{i:03d}_{j:03d}",title_ref=f"SYN_CALENDAR_PRIVATE_{i:03d}_{j:03d}",
      start_offset_sec=t+3600*(j+1)) for j in range(1,ncal+1)]
    airplane="on" if i%13==0 else "off"
    connected=False if airplane=="on" else i%9!=0
    wifi=connected and i%3!=0
    transport = "WIFI" if wifi else "CELLULAR" if connected else "NONE"
    mVol=R.choice([7,10,12,15,20,25])
    ring=0 if i%4==0 else R.randint(1,mVol)
    sdk=[29,30,31,32,33,34,35,36][i%8]
    permissions={p: (["granted","denied","unknown"][(i+j)%3] if i%5==0 else
           ["granted","granted","granted","denied","unknown"][(i+2*j)%5])
       for j,p in enumerate(["contacts","media_images","notifications","accessibility","microphone","location","calendar"])}
    env=dict(
      environment_id=eid,captured_offset_sec=t,
      initialization_data=dict(
        user_profile=dict(user_id=uid,age_band=ages[(i*3)%len(ages)],locale="zh-TW",
                          font_scale=[1.0,1.15,1.3,1.4,1.6,1.8][i%6]),
        device=dict(device_id=f"SYN_DEVICE{i:03d}",manufacturer="SYN_BRAND",
             model=f"SYN_MODEL_{chr(65+i%7)}",sdk_int=sdk,android_release=str(sdk-20) if sdk!=36 else "16",
             screen_width_px=R.choice([720,1080,1170,1440]),screen_height_px=R.choice([1600,2160,2340,2400]),
             ram_gb=R.choice([3,4,6,8,12]),storage_gb=R.choice([32,64,128,256])),
        contacts=contacts,installed_apps=approws),
      continuous_state_data=dict(
        permissions=permissions,
        battery=dict(capacity_percent=R.randint(4,99),charging=bool(i%3==0)),
        network=dict(connected=connected,transport=transport,internet_validated=(connected and i%8!=0),
             wifi_ssid_ref=f"SYN_WIFI_PRIVATE_{i:03d}" if wifi else None),
        system=dict(airplane_mode=airplane,dnd_mode="on" if i%3==0 else "off",ringer_mode=["normal","silent","vibrate"][i%3],
              ring_volume_current=ring,ring_volume_max=mVol),
        screen=dict(current_app_id=pick(appids) if appids else None,guard_ready=(i%7!=0),
              node_count=R.randint(0,80),text_ref=f"SYN_SCREEN_PRIVATE_{i:03d}"),
        photos=photos,messages=messages,notifications=nots,calendar=calendar,
        location=dict(location_ref=f"SYN_LOCATION_PRIVATE_{i:03d}",latitude=0,longitude=0,accuracy_m=R.choice([10,25,50,100,250])),
        clipboard=dict(observability="unavailable",text_ref=None),
        call=dict(state=["idle","ringing","offhook"][i%3],caller_ref=None)
      ))
    return env

source = copy.deepcopy(SEED)
source["provenance"]="AI_ASSISTED_SCENARIO_DESIGN_SEE_README_PROGRAMMATIC_SYNTHETIC_NO_REAL_DEVICE_VALUES"
e0=source['phone_environments'][0]
envs=[e0]+[synth_environment(i) for i in range(2,73)]
assert len(envs)==72
source["phone_environments"]=envs
tasks=copy.deepcopy(source['task_requests'])
condition_counts=collections.Counter()
for envix,env in enumerate(envs,1):
    eid=env["environment_id"]; uid=env["initialization_data"]["user_profile"]["user_id"]
    st=env["continuous_state_data"]; init=env["initialization_data"]
    start_idx = 1 if envix==1 else 0 # existing 3 tasks first env
    for sc_index, scenario in enumerate(["SHARE_PHOTO","CHECK_MESSAGE","RESCUE_PHONE"]):
        for rep in range(2 if envix!=1 else 1):
            n=len(tasks)+1; tid=f"SYN_T{n:04d}"
            privacy_mode="STRICT_LOCAL" if (envix+sc_index+rep)%4==0 else "MINIMIZED_CLOUD"
            confirmed = ["approved","pending","rejected","not_required"][(envix+sc_index*2+rep)%4]
            cancelled = (envix+sc_index+rep)%13==0
            captured=env["captured_offset_sec"]
            base_grants={
                "SHARE_PHOTO":["voice_command","recipient_binding","selected_photo","share_app_state"],
                "CHECK_MESSAGE":["voice_command","selected_message"],
                "RESCUE_PHONE":["voice_command","system_status","network_state"],
            }[scenario].copy()
            # Some cases deliberately omit a task-grant; distinguish from platform permissions
            if (envix+sc_index+rep)%9==0:
                base_grants.remove(base_grants[-1])
                condition_counts["purpose_grant_missing"]+=1
            if privacy_mode=="MINIMIZED_CLOUD" and (envix+rep)%7!=0:
                base_grants.append("cloud_features")
            params={}
            if scenario=="SHARE_PHOTO":
                relation = pick(["daughter","son","granddaughter","grandson","friend"])
                candidates=[c for c in init["contacts"] if c["relationship"]==relation]
                if candidates and (envix+rep)%4!=0: relation=pick(candidates)["relationship"]
                ph=st["photos"]
                params={"recipient_relation":relation,
                  "photo_id":(pick(ph)["photo_id"] if ph and (envix+rep)%5!=0 else None)}
                if ph and params["photo_id"] is None: condition_counts["selected_photo_missing"]+=1
            elif scenario=="CHECK_MESSAGE":
                ms=st["messages"]
                params={"message_id":(pick(ms)["message_id"] if ms and (envix+rep)%5!=0 else None)}
                if params["message_id"] is None: condition_counts["selected_message_missing"]+=1
            else: params={}
            if privacy_mode=="STRICT_LOCAL": condition_counts["strict_local"]+=1
            if cancelled: condition_counts["cancelled"]+=1
            if confirmed=="rejected": condition_counts["confirmation_rejected"]+=1
            tasks.append(dict(task_id=tid,environment_id=eid,user_id=uid,scenario_id=scenario,
              utterance=pick(PHRASES[scenario]),privacy_mode=privacy_mode,grants=base_grants,
              confirmation=confirmed,cancelled=cancelled,requested_offset_sec=captured+10+rep*10+sc_index*20,
              parameters=params))
source['task_requests']=tasks
assert len(tasks)==432

extra_tasks=[]
future_scs=["CALL_CONTACT","SEND_MESSAGE","READ_NOTIFICATION","SET_REMINDER","NAVIGATE_TO_PLACE","OPEN_APP"]
for ei,env in enumerate(envs):
    for offset in (0,1):
        sc=future_scs[(ei*2+offset)%len(future_scs)]
        eid=env["environment_id"]; uid=env['initialization_data']['user_profile']['user_id']
        params={}
        if sc in ("CALL_CONTACT","SEND_MESSAGE"):
            opts=env["initialization_data"]["contacts"]
            params={"recipient_relation":(pick(opts)["relationship"] if opts else "daughter"),
                    "message_text":"虛構家人提醒：今晚七點回家。" if sc=="SEND_MESSAGE" else None}
        elif sc=="READ_NOTIFICATION":
            ns=env["continuous_state_data"]["notifications"]
            params={"notification_id":pick(ns)["notification_id"] if ns else None}
        elif sc=="SET_REMINDER":
            params={"reminder_kind":pick(["checkup","medicine","bill","family_event"]),"time_offset_sec":R.choice([1800,3600,7200,86400])}
        elif sc=="NAVIGATE_TO_PLACE":params={"destination_ref":f"SYN_DESTINATION_{ei:03d}"}
        else:params={"app_name":pick(["LINE","GALLERY","SETTINGS","MAPS"])}
        ext= dict(task_id=f"SYN_EXT_T{len(extra_tasks)+1:04d}",environment_id=eid,user_id=uid,
          scenario_id=sc,utterance=pick(PHRASES[sc]),
          privacy_mode="STRICT_LOCAL" if (ei+offset)%3==0 else "MINIMIZED_CLOUD",
          grants=["voice_command"],confirmation=["approved","pending","not_required","rejected"][(ei+offset)%4],
          cancelled=(ei+offset)%17==0,requested_offset_sec=env["captured_offset_sec"]+80+offset*10,
          parameters=params)
        extra_tasks.append(ext)
assert len(extra_tasks)==144


# Repair the URL validation (only *.example.invalid is permitted in synthetic texts).
import re
def validate_raw(src, requested_tasks):
    env_map = {e["environment_id"]: e for e in src["phone_environments"]}
    assert len(env_map) == len(src["phone_environments"])
    assert len({t["task_id"] for t in requested_tasks}) == len(requested_tasks)
    for e in env_map.values():
        assert e["initialization_data"]["user_profile"]["user_id"].startswith("SYN_U")
        loc = e["continuous_state_data"]["location"]
        assert loc["latitude"] == 0 and loc["longitude"] == 0
        contacts = e["initialization_data"]["contacts"]
        apps = {a["app_id"] for a in e["initialization_data"]["installed_apps"]}
        photo_ids = {p["photo_id"] for p in e["continuous_state_data"]["photos"]}
        messages = {m["message_id"] for m in e["continuous_state_data"]["messages"]}
        assert len({c["contact_id"] for c in contacts}) == len(contacts)
        assert len(photo_ids) == len(e["continuous_state_data"]["photos"])
        assert len(messages) == len(e["continuous_state_data"]["messages"])
        for m in e["continuous_state_data"]["messages"]:
            urls = re.findall(r"https?://[^\s，。]+", m["text"])
            assert all(u.split("/")[2].endswith(".example.invalid") for u in urls), urls
            assert m["received_offset_sec"] <= e["captured_offset_sec"] + 1
        for n in e["continuous_state_data"]["notifications"]:
            assert n["message_id"] in messages
            assert n["app_id"] in apps
        for p in e["continuous_state_data"]["photos"]:
            assert p["pixels_available"] is False
    for t in requested_tasks:
        e = env_map[t["environment_id"]]
        assert t["user_id"] == e["initialization_data"]["user_profile"]["user_id"]
        assert t["requested_offset_sec"] >= e["captured_offset_sec"]
        assert t["privacy_mode"] in ("STRICT_LOCAL", "MINIMIZED_CLOUD")
        pp = t["parameters"]
        if pp.get("message_id"):
            assert pp["message_id"] in {m["message_id"] for m in e["continuous_state_data"]["messages"]}
        if pp.get("photo_id"):
            assert pp["photo_id"] in {p["photo_id"] for p in e["continuous_state_data"]["photos"]}

validate_raw(source,tasks)
validate_raw(source,extra_tasks)
out=OUT
def serialize_cell(value):
    if value is None: return "null"
    if isinstance(value, bool): return "true" if value else "false"
    if isinstance(value, (list,dict)): return json.dumps(value,ensure_ascii=False,separators=(",",":"))
    return str(value)

def to_env_row(env, columns):
    fields={}
    def walk(obj, prefix=""):
        for k,v in obj.items():
            key = f"{prefix}.{k}" if prefix else k
            if isinstance(v,dict): walk(v,key)
            else: fields[key]=serialize_cell(v)
    walk(env)
    return {k: fields.get(k,"") for k in columns}

with open(str(out/'seed_phone_environments.csv'),encoding='utf-8-sig',newline='') as f:
    env_cols=csv.DictReader(f).fieldnames

with open(str(out/'seed_task_requests.csv'),encoding='utf-8-sig',newline='') as f:
    task_cols=csv.DictReader(f).fieldnames

def task_to_row(task, cols):
    row={k: "" for k in cols}
    for k,v in task.items():
        if k != "parameters": row[k]=serialize_cell(v)
    for k,v in task["parameters"].items():
        row[f"parameters.{k}"]=serialize_cell(v) if v is not None else "null"
    return {k:row.get(k,"") for k in cols}

def write_csv(filename, cols, rows):
    p=out/filename
    with p.open('w',encoding='utf-8-sig',newline='') as f:
        w=csv.DictWriter(f,fieldnames=cols)
        w.writeheader()
        w.writerows(rows)
    return p

source_path=out/"source_expanded_v3.json"
source_path.write_text(json.dumps(source,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
p_env=write_csv("phone_environments_expanded.csv",env_cols,[to_env_row(e,env_cols) for e in envs])
p_task=write_csv("task_requests_expanded.csv",task_cols,[task_to_row(t,task_cols) for t in tasks])

extension_cols=list(task_cols)+["parameters.message_text","parameters.notification_id","parameters.reminder_kind","parameters.time_offset_sec","parameters.destination_ref","parameters.app_name"]
source_extension=dict(bundle_version="4.0-candidate-only",synthetic=True,
  provenance="AI_ASSISTED_SCENARIO_DESIGN_PROGRAMMATIC_SYNTHETIC_NO_REAL_DEVICE_VALUES",
  compatible_processor="NOT_COMPATIBLE_WITH_V3_PRIVACY_ENGINE",
  phone_environments=envs,task_requests=extra_tasks)
(out/"source_extension_v4_candidate.json").write_text(json.dumps(source_extension,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
p_extension=write_csv("task_requests_extension_v4_candidate.csv",extension_cols,[task_to_row(t,extension_cols) for t in extra_tasks])

scenario_counts=collections.Counter(t["scenario_id"] for t in tasks+extra_tasks)
base_counts=collections.Counter(t["scenario_id"] for t in tasks)
ext_counts=collections.Counter(t["scenario_id"] for t in extra_tasks)
mode_counts=collections.Counter(t["privacy_mode"] for t in tasks+extra_tasks)
confirmation_counts=collections.Counter(t["confirmation"] for t in tasks+extra_tasks)
missing_photo=sum(t["scenario_id"]=="SHARE_PHOTO" and t["parameters"].get("photo_id") is None for t in tasks)
missing_msg=sum(t["scenario_id"]=="CHECK_MESSAGE" and t["parameters"].get("message_id") is None for t in tasks)
with open(out/"scenario_catalog.csv","w",newline="",encoding="utf-8-sig") as f:
    w=csv.writer(f);w.writerow(["scenario_id","base_or_extension","task_count","supported_by_v3_processor","description_zh"])
    zh=dict(SHARE_PHOTO="選定照片分享",CHECK_MESSAGE="可疑訊息線索判讀",RESCUE_PHONE="手機聲音與網路設定排除",
            CALL_CONTACT="撥打聯絡人",SEND_MESSAGE="傳送訊息",READ_NOTIFICATION="讀取通知",SET_REMINDER="建立提醒",
            NAVIGATE_TO_PLACE="導航至指定地點",OPEN_APP="開啟 App")
    for sc in list(base_counts)+list(ext_counts): w.writerow([sc,"v3" if sc in base_counts else "v4_candidate",scenario_counts[sc],
                                                  "yes" if sc in base_counts else "no",zh[sc]])

report=dict(
  dataset_version="AI_ASSISTED_RAW_V4_CANDIDATE_20261012",
  synthetic=True,
  generator="ChatGPT-authored scenario templates + deterministic Python composition; not Gemini API generation per record",
  seed=20261012, source_schema_version="3.0",
  phone_environments=len(envs),tasks_v3_compatible=len(tasks),tasks_extension_only=len(extra_tasks),
  total_task_requests=len(tasks)+len(extra_tasks),
  scenarios_v3=dict(base_counts),scenarios_extension_only=dict(ext_counts),
  privacy_modes=dict(mode_counts),confirmation=dict(confirmation_counts),
  cancelled=sum(t["cancelled"] for t in tasks+extra_tasks),
  base_missing_photo=missing_photo,base_missing_message=missing_msg,
  validations=["Unique environment/task IDs","valid environment/user references","valid selected photo and message references",
               "valid notification references","synthetic 0/0 location","*.example.invalid links only",
               "photo pixels unavailable","task timestamps after snapshot","two privacy modes"],
  limitations=[
     "Not collected from real individuals or a representative older-adult population",
     "Raw AI-assisted synthetic source only; no expanded derived_tasks.csv created",
     "Original v3 processing engine was not supplied or run",
     "v3 processor accepts only the three supported scenarios and may reject non-whitelisted generated messages",
     "Six additional task types are v4 candidate schema and require future new processing rules",
     "No privacy protection effect, re-identification resistance, task accuracy or real Android action claimed",
     "Original v3 baseline derived file remains exactly three tasks, separate from this expanded raw data"
  ])
(out/"quality_report.json").write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding='utf-8')

# CSV roundtrip verifies JSON text columns and all rows.
for path, expected in [(p_env,len(envs)),(p_task,len(tasks)),(p_extension,len(extra_tasks))]:
    with path.open(encoding="utf-8-sig",newline="") as f:
        rows=list(csv.DictReader(f))
        assert len(rows)==expected
        for rr in rows:
            for k,v in rr.items():
                if k in ("grants","initialization_data.contacts","continuous_state_data.messages"):
                    json.loads(v)
assert json.loads(source_path.read_text(encoding="utf-8"))['task_requests']==tasks
print("Generated",len(envs),"env,",len(tasks),"base tasks and",len(extra_tasks),"extension tasks")
print("Counts:",dict(scenario_counts))
print("Files:",[(p.name,round(p.stat().st_size/1024)) for p in out.iterdir() if p.is_file()])
