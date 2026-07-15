import os,time
from dotenv import load_dotenv
from google import genai
from google.genai import types,errors
import device

load_dotenv()
client = genai.Client(api_key=os.getenv("GEMINI_API_KEY"))

# [vs quickstart] quickstart는 모든 액션을 구현(list_apps/take_screenshot 포함). 우린 안 쓰는 2개를 excluded로 아예 차단.
tools = [types.Tool(computer_use=types.ComputerUse(environment=types.Environment.ENVIRONMENT_MOBILE,excluded_predefined_functions=["list_apps", "take_screenshot"],))]

SYSTEM_PROMPT = """You are an agent operating a real Android phone. You see a screenshot each step and issue one UI action.

Rules:
- The current screenshot is the ground truth. The action history is only intent and may have failed — always re-check the screen before acting.  # 화면=절대 사실(open_app 성공 착각해서 헤매는 거 방지)
- Do NOT invent products, brands, or names that are not in the task or visible on screen. If the task names no specific item, do not guess one — stop and ask.  # RESCENE 환각 차단
- Before concluding something is missing, scroll to check the whole screen.  # 스크롤 강제(성급한 판정 방지)
- You can open any app directly by its package name with open_app.  # package로 직접 앱 열기
- Enter text only with the type tool, never by tapping the on-screen keyboard.  # type 도구만 쓰기(한글·특수문자 안전)
- If the task is ambiguous, impossible, or already done, stop and say so instead of guessing."""  # 모호·불가·완료면 멈추기

cfg = types.GenerateContentConfig(
    tools=tools,
    system_instruction=SYSTEM_PROMPT,
    temperature=0.0,  # 같은 화면이면 같은 판단 — 무작위 헤맴·환각 감소.
)

TASK = input("어떤 작업이 필요하신가요?").strip()
bridge = device.ADBBridge()
bridge.enable_adb_keyboard()
img = bridge.screenshot()
contents = [types.Content(role="user",
                          parts=[types.Part(text=TASK),
                                 types.Part.from_bytes(data=img, mime_type="image/png"),])]

# [vs quickstart] quickstart엔 재시도 로직이 없음 — 우리만의 안전장치(429/5xx일 때 대기 후 재시도).
def generate_with_retry(contents,max_tries=4):
    for attempt in range(1, max_tries + 1):
        try:
            # [vs quickstart] API 방식이 다름: 우린 generate_content + contents를 직접 누적(히스토리를 내가 관리).
            #   quickstart는 client.interactions.create + previous_interaction_id(히스토리를 서버가 관리).
            #   → 우리 방식이라야 재시도 유지 + 나중에 히스토리 가지치기(토큰 절감)가 가능.
            return client.models.generate_content(
                model="gemini-3.5-flash",contents=contents,config=cfg
            )
        except errors.APIError as e:
            if e.code == 429 or e.code >= 500:
                wait = 2 * attempt
                print(f" API 에러 {e.code} - {wait}초 후 재시도({attempt}/{max_tries})")
                time.sleep(wait)
            else :
                raise
    raise RuntimeError(f"API 재시도 {max_tries}회 초과 - 중단")
# [vs quickstart] 우린 for range + for-else로 최대스텝(30) 도달을 판정. quickstart는 while turn < max_turns(=100).
try :  # 완료·에러·최대스텝·거부 어디서 끝나든 무조건 원래 키보드 복원하려고 try/finally 래핑.
    for step in range(1,16):
        resp = generate_with_retry(contents)
        Show_Usage = False
        if Show_Usage:
            print(resp.usage_metadata)
        model_content = resp.candidates[0].content
        
        # 응답에서 액션 찾기
        fc = None
        for part in (model_content.parts or []):
            if part.function_call:
                fc = part.function_call
            elif part.text:
                print(f"[{step}] {part.text}")
        
        if fc is None:
            print("작업 완료!")
            break
        
        print(f"[{step}] 액션: {fc.name}  {dict(fc.args)}")
        args = dict(fc.args)
        
        # [vs quickstart] 안전확인: quickstart는 묻지 않고 자동 승인(safety_acknowledgement=True).
        #   우린 사용자에게 y/n 확인 후 진행 — 우리 쪽이 더 안전(HITL).
        ack=False
        sd=args.get("safety_decision")
        if sd and sd.get("decision") == "require_confirmation":
            print(f" 확인 필요 : {sd.get('explanation')}")
            answer = input(" 실행할까요 (y/n)")
            if answer.strip().lower() != "y":
                print(" 사용자가 거부 -> 중단")
                break
            ack = True
        
        contents.append(model_content)
        
        # [vs quickstart] getattr dispatch + try/except는 quickstart에서 차용(예전 run_action if/elif 대체).
        #   실패해도 크래시 대신 error를 모델에 되먹여 자가복구 유도 → 실전에서 한글입력 실패 우회로 검증됨.
        handler = getattr(bridge,fc.name,None)
        if handler is None:
            result = {"status": "error","error" : f"미지원 액션 : {fc.name}"}
        else : 
            try : 
                handler (**args)
                result = {"status" : "ok"}
            except Exception as e:
                result = {"status" : "error","error": str(e)}
                print(f" ! 액션 실패 : {e}")
        time.sleep(0.5)
        
        payload = result
        if ack:
            payload["safety_acknowledgement"] = "true"
        new_img = bridge.screenshot()
        
        feedback = types.FunctionResponse(
            name=fc.name,
            response= payload,
            parts=[types.FunctionResponsePart.from_bytes(
                data=new_img, mime_type="image/png"
            )],
        )
        if fc.id:
            feedback.id = fc.id
        contents.append(types.Content(role="user", parts=[types.Part(function_response=feedback)]))
    else :
        print("최대 스텝 도달 - 작업 미완료 중단")
finally :
    bridge.restore_keyboard()  # 사용자가 원래 키보드로 타이핑 가능하게 복원.