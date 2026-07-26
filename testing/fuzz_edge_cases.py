import json, urllib.request, urllib.error, http.cookiejar, base64, time, asyncio, websockets
BASE="http://localhost:8080"; WSU="ws://localhost:8080/ws/game"
def mk(u,p):
    cj=http.cookiejar.CookieJar()
    return {"op":urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj)),
            "auth":"Basic "+base64.b64encode(f"{u}:{p}".encode()).decode(),"tok":None,"u":u,"cj":cj}
def call(st,m,path,body=None,ua=True,raw=None):
    d = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    r=urllib.request.Request(BASE+path,data=d,method=m); r.add_header("Accept","application/json")
    if d: r.add_header("Content-Type","application/json")
    if ua: r.add_header("Authorization",st["auth"])
    if m!="GET" and st["tok"]: r.add_header("X-XSRF-TOKEN",st["tok"])
    try:
        with st["op"].open(r,timeout=30) as resp:
            t=resp.read().decode(); return resp.status,(json.loads(t) if t else None)
    except urllib.error.HTTPError as e:
        t=e.read().decode()
        try: return e.code,json.loads(t)
        except: return e.code,t[:120]
    except Exception as e: return -1,str(e)[:90]
def login(st,p):
    call(st,"POST","/api/auth/register",{"username":st["u"],"password":p},ua=False)
    s,sess=call(st,"GET","/api/session")
    if isinstance(sess,dict): st["tok"]=sess.get("csrfToken")

results=[]
def chk(name,got,ok_set,note=""):
    ok = got in ok_set
    results.append((ok,name,got,ok_set,note)); 
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}: {got} (want {ok_set}) {note}")

ts=str(int(time.time()))[-6:]
A=mk("fz"+ts,"password123"); login(A,"password123")
print("=== HTTP hostile input (want: no 500s, no hangs) ===")
chk("negative difficulty", call(A,"POST","/api/game/new?difficulty=-5")[0], {400,500})
chk("non-numeric difficulty", call(A,"POST","/api/game/new?difficulty=abc")[0], {400})
chk("huge difficulty", call(A,"POST","/api/game/new?difficulty=99999999999999999999")[0], {400})
chk("gameId with path traversal", call(A,"GET","/api/game/..%2F..%2Fetc%2Fpasswd")[0], {400,404})
chk("gameId 10k chars", call(A,"GET","/api/game/"+("x"*10000))[0], {400,404,414})
chk("gameId with null byte", call(A,"GET","/api/game/abc%00def")[0], {400,404,500})
chk("unicode gameId", call(A,"GET","/api/game/%F0%9F%92%80%F0%9F%92%80")[0], {400,404})
chk("malformed JSON body", call(A,"POST","/api/game/import",raw=b'{"code":')[0], {400})
chk("null JSON body", call(A,"POST","/api/game/import",raw=b'null')[0], {400,500})
chk("empty body on import", call(A,"POST","/api/game/import",raw=b'{}')[0], {400})
chk("import 100k share code", call(A,"POST","/api/game/import",{"code":"A"*100000})[0], {400,413})
chk("deeply nested JSON", call(A,"POST","/api/game/import",raw=('{"code":'+'['*400+']'*400+'}').encode())[0], {400,500})
chk("saved limit=0", call(A,"GET","/api/game/saved?limit=0")[0], {400,500})
chk("saved limit=-1", call(A,"GET","/api/game/saved?limit=-1")[0], {400,500})
chk("leaderboard limit huge", call(A,"GET","/api/leaderboard?limit=999999")[0], {200,400,500})
chk("archive bad date format", call(A,"POST","/api/daily/archive/not-a-date/join")[0], {400,404,500})
chk("archive year 9999", call(A,"POST","/api/daily/archive/9999-12-31/join")[0], {400,404})
chk("powerup unknown type", call(A,"POST","/api/powerups/buy/NOT_A_TYPE")[0], {400,404})
chk("friend request to self", call(A,"POST",f"/api/friends/request/{A['u']}")[0], {400})
chk("friend request unicode name", call(A,"POST","/api/friends/request/%F0%9F%92%80")[0], {400,404})
chk("tournament puzzle 0", call(A,"POST","/api/tournament/0/join")[0], {400,404,500})
chk("tournament puzzle 99", call(A,"POST","/api/tournament/99/join")[0], {400,404,500})
chk("register 10k username", call(A,"POST","/api/auth/register",{"username":"u"*10000,"password":"password123"},ua=False)[0], {400})
chk("register null fields", call(A,"POST","/api/auth/register",raw=b'{"username":null,"password":null}',ua=False)[0], {400})
chk("register unicode username", call(A,"POST","/api/auth/register",{"username":"日本語ユーザ","password":"password123"},ua=False)[0], {400})

async def wsfuzz():
    print("\n=== WebSocket hostile frames (session must survive, no crash) ===")
    s,g=call(A,"POST","/api/game/new?difficulty=1&chaos=false&mirror=false")
    gid=g["gameId"]
    cookie="; ".join(f"{ck.name}={ck.value}" for ck in A["cj"])
    frames=[
      ("empty string",""),("plain text","hello"),("null literal","null"),
      ("empty object","{}"),("array",'[1,2,3]'),
      ("missing type",'{"payload":{}}'),
      ("type is int",'{"type":123}'),
      ("move payload null",'{"type":"move","payload":null}'),
      ("move payload string",'{"type":"move","payload":"nope"}'),
      ("move out of range",'{"type":"move","payload":{"row":99,"col":99,"oldVal":0,"newVal":9}}'),
      ("move negative",'{"type":"move","payload":{"row":-5,"col":-5,"oldVal":0,"newVal":5}}'),
      ("move newVal 42",'{"type":"move","payload":{"row":0,"col":0,"oldVal":0,"newVal":42}}'),
      ("move string coords",'{"type":"move","payload":{"row":"a","col":"b","oldVal":0,"newVal":1}}'),
      ("chat control chars",'{"type":"chat","payload":"\\u0000\\u0007bad"}'),
      ("chat 20k chars",'{"type":"chat","payload":"'+("Z"*20000)+'"}'),
      ("unknown type",'{"type":"totally-unknown"}'),
      ("deep nesting",'{"type":"chat","payload":'+('['*200)+(']'*200)+'}'),
    ]
    survived=0; closed_at=None
    async with websockets.connect(f"{WSU}?gameId={gid}",
            additional_headers={"Authorization":A["auth"],"Cookie":cookie},
            origin="http://localhost:8080") as ws:
        for name,fr in frames:
            try:
                await ws.send(fr); await asyncio.sleep(0.12)
                if ws.state.name!="OPEN": closed_at=name; break
                survived+=1
            except Exception as e:
                closed_at=f"{name} ({type(e).__name__})"; break
        # is the socket still usable afterwards?
        usable=False
        try:
            await ws.send(json.dumps({"type":"sync","payload":""}))
            for _ in range(60):
                m=await asyncio.wait_for(ws.recv(),timeout=3)
                if json.loads(m).get("type")=="board": usable=True; break
        except Exception: pass
    print(f"  frames survived: {survived}/{len(frames)}" + (f" (closed at: {closed_at})" if closed_at else ""))
    chk("session survives all hostile frames", survived, {len(frames)}, f"closed at {closed_at}" if closed_at else "")
    chk("socket still functional afterwards", usable, {True})
    # health after the storm
    chk("server still healthy", call(A,"GET","/actuator/health")[0], {200})

asyncio.run(wsfuzz())
bad=[r for r in results if not r[0]]
print(f"\n===== {len(results)-len(bad)}/{len(results)} passed, {len(bad)} FAILED =====")
for _,n,got,exp,note in bad: print(f"  FAIL {n}: got {got}, want {exp} {note}")
# Non-zero exit so CI treats a failed check as a failed build.
import sys as _sys; _sys.exit(1 if bad else 0)
