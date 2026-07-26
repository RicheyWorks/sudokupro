import json, urllib.request, urllib.error, http.cookiejar, base64, time

BASE="http://localhost:8080"
def mk(user,pw):
    cj=http.cookiejar.CookieJar()
    op=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    auth="Basic "+base64.b64encode(f"{user}:{pw}".encode()).decode()
    st={"op":op,"auth":auth,"tok":None,"cj":cj,"u":user}
    return st

def call(st,method,path,body=None,use_auth=True,use_csrf=True,as_json=True):
    data=json.dumps(body).encode() if body is not None else None
    r=urllib.request.Request(BASE+path,data=data,method=method)
    r.add_header("Accept","application/json")
    if data: r.add_header("Content-Type","application/json")
    if use_auth: r.add_header("Authorization",st["auth"])
    if method!="GET" and use_csrf and st["tok"]: r.add_header("X-XSRF-TOKEN",st["tok"])
    try:
        with st["op"].open(r,timeout=30) as resp:
            t=resp.read().decode(); return resp.status,(json.loads(t) if t and as_json else t)
    except urllib.error.HTTPError as e:
        t=e.read().decode()
        try: return e.code,json.loads(t)
        except: return e.code,t
    except Exception as e:
        return -1,str(e)

def login(st,pw):
    call(st,"POST","/api/auth/register",{"username":st["u"],"password":pw},use_auth=False,use_csrf=False)
    s,sess=call(st,"GET","/api/session")
    if isinstance(sess,dict): st["tok"]=sess.get("csrfToken")
    return s

ts=str(int(time.time()))[-6:]
A=mk("alice"+ts,"password123"); B=mk("bob"+ts,"password123")
print("alice session:",login(A,"password123"),"| bob session:",login(B,"password123"))

s,g=call(A,"POST","/api/game/new?difficulty=1&chaos=false&mirror=false")
gid=g["gameId"]; print("alice game:",gid)

results=[]
def check(name, got, expected, note=""):
    ok = got in expected if isinstance(expected,(list,tuple,set)) else got==expected
    results.append((ok,name,got,expected,note))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}: got {got}, want {expected} {note}")

print("\n=== ownership / authorization ===")
check("bob cannot save alice's game", call(B,"POST",f"/api/game/{gid}/save",as_json=False)[0], [403])
check("bob cannot end alice's game",  call(B,"POST",f"/api/game/{gid}/end",as_json=False)[0], [403])
check("bob cannot resume alice's game", call(B,"POST",f"/api/game/{gid}/resume",as_json=False)[0], [403,409])
check("bob CAN read alice's board (spectating by design)", call(B,"GET",f"/api/game/{gid}")[0], [200])

print("\n=== hint charged to the caller, not the board owner ===")
before_a=call(A,"GET","/api/economy/wallet")[1]["gems"]
before_b=call(B,"GET","/api/economy/wallet")[1]["gems"]
hs,_=call(B,"POST",f"/api/game/hint?gameId={gid}")
after_a=call(A,"GET","/api/economy/wallet")[1]["gems"]
after_b=call(B,"GET","/api/economy/wallet")[1]["gems"]
print(f"  hint HTTP {hs}: alice {before_a}->{after_a}, bob {before_b}->{after_b}")
check("victim's gems NOT drained by attacker's hint", after_a, [before_a],
      "<-- V3: attacker drains owner's wallet" if after_a<before_a else "")

print("\n=== authentication boundaries ===")
anon=mk("nobody","nopass"); anon["tok"]=None
check("anonymous wallet blocked", call(anon,"GET","/api/economy/wallet",use_auth=False)[0], [401])
check("anonymous new game blocked", call(anon,"POST","/api/game/new?difficulty=1",use_auth=False,as_json=False)[0], [401,403])
check("bad password rejected", call(mk("alice"+ts,"WRONGPASS"),"GET","/api/session")[0], [401,429])

print("\n=== CSRF ===")
noc=mk(A["u"],"password123"); login(noc,"password123"); noc["tok"]=None
check("mutating call without CSRF token rejected",
      call(noc,"POST","/api/game/new?difficulty=1",use_csrf=False,as_json=False)[0], [403])

print("\n=== input validation ===")
check("difficulty 99 rejected", call(A,"POST","/api/game/new?difficulty=99",as_json=False)[0], [400,500])
check("difficulty 0 rejected",  call(A,"POST","/api/game/new?difficulty=0",as_json=False)[0], [400,500])
check("unknown gameId -> 404",  call(A,"GET","/api/game/does-not-exist")[0], [404])
check("garbage share code -> 400", call(A,"POST","/api/game/import",{"code":"!!!not-base64!!!"})[0], [400])
check("saved limit over cap rejected", call(A,"GET","/api/game/saved?limit=9999")[0], [400,500])

print("\n=== registration hygiene ===")
check("duplicate username rejected", call(A,"POST","/api/auth/register",{"username":A["u"],"password":"password123"},use_auth=False,use_csrf=False,as_json=False)[0], [409,400])
check("short password rejected", call(A,"POST","/api/auth/register",{"username":"zz"+ts,"password":"short"},use_auth=False,use_csrf=False,as_json=False)[0], [400])
check("reserved name 'admin' rejected", call(A,"POST","/api/auth/register",{"username":"admin","password":"password123"},use_auth=False,use_csrf=False,as_json=False)[0], [400])
check("bad charset username rejected", call(A,"POST","/api/auth/register",{"username":"a b/c","password":"password123"},use_auth=False,use_csrf=False,as_json=False)[0], [400])

print("\n=== solution never leaks over the wire ===")
s,fresh=call(A,"POST","/api/game/new?difficulty=3&chaos=false&mirror=false")
leak=sum(1 for row in fresh["cells"] for c in row if (not c["isGiven"]) and c["value"]!=0)
check("no pre-filled non-given cells in BoardState", leak, [0])
check("no 'solution' key in payload", "solution" in json.dumps(fresh).lower(), [False])

print("\n=== auto-solve ownership (board destruction + solution oracle) ===")
s,vg=call(A,"POST","/api/game/new?difficulty=1&chaos=false&mirror=false")
vgid=vg["gameId"]
filled_before=sum(1 for row in vg["cells"] for c in row if c["value"]!=0)
sc,_=call(B,"POST",f"/api/game/{vgid}/solve")
check("attacker cannot auto-solve another player's board", sc, [403])
s,after=call(A,"GET",f"/api/game/{vgid}")
filled_after=sum(1 for row in after["cells"] for c in row if c["value"]!=0) if isinstance(after,dict) else -1
check("victim's grid untouched after attempted solve", filled_after, [filled_before])
check("victim's game not marked solved", after.get("solved") if isinstance(after,dict) else None, [False])

print("\n=== shared daily template cannot be poisoned ===")
s,dstat=call(A,"GET","/api/daily")
today=dstat.get("date")
call(A,"POST","/api/daily/join")           # ensure the template row exists
tc,_=call(B,"POST",f"/api/game/daily-{today}/solve")
check("shared daily template is not solvable by a passer-by", tc, [403,404])
C=mk("carol"+ts,"password123"); login(C,"password123")
s,cb=call(C,"POST","/api/daily/join")
cfill=sum(1 for row in cb["cells"] for c in row if c["value"]!=0) if isinstance(cb,dict) else -1
check("a later joiner still gets an unsolved daily", cb.get("solved") if isinstance(cb,dict) else None, [False])
check("a later joiner's daily is not pre-filled", cfill < 81, [True])

print("\n=== archive cannot serve TODAY (solution oracle) ===")
ac,_=call(A,"POST",f"/api/daily/archive/{today}/join")
check("archive refuses today's date", ac, [400,404])


print("\n=== share-code import cannot mint currency ===")
import gzip as _gzip
def _solve(g):
    for r in range(9):
        for c in range(9):
            if g[r][c]==0:
                for v in range(1,10):
                    if all(g[r][x]!=v for x in range(9)) and all(g[y][c]!=v for y in range(9)) \
                       and all(g[(r//3)*3+i][(c//3)*3+j]!=v for i in range(3) for j in range(3)):
                        g[r][c]=v
                        if _solve(g): return True
                        g[r][c]=0
                return False
    return True
_grid=[[0]*9 for _ in range(9)]; _solve(_grid)

def _share(cells):
    return base64.urlsafe_b64encode(_gzip.compress(json.dumps(cells).encode())).rstrip(b"=").decode()

# A completed grid, every cell claimed as a legitimate PLAYER move. Before the fix this
# imported as an already-solved board and POST /end paid out in full, every time, with a
# fresh gameId each import so the rewards-granted replay guard never fired.
_solved_cells=[[{"v":_grid[r][c],"g":False,"ms":"PLAYER"} for c in range(9)] for r in range(9)]
s,w0=call(A,"GET","/api/economy/wallet")
gems_before = w0.get("gems") if isinstance(w0,dict) else -1
for _ in range(4):
    sc,ib=call(A,"POST","/api/game/import",{"code":_share(_solved_cells)})
    if sc==200 and isinstance(ib,dict):
        check("an imported grid is never already solved", ib.get("solved"), [False])
        call(A,"POST",f"/api/game/{ib['gameId']}/end")
s,w1=call(A,"GET","/api/economy/wallet")
gems_after = w1.get("gems") if isinstance(w1,dict) else -2
check("four import+end cycles mint no gems", gems_after, [gems_before],
      f"({gems_before} -> {gems_after})")

# The obvious follow-up: claim all 81 cells are clues instead.
_all_given=[[{"v":_grid[r][c],"g":True,"ms":"INITIAL"} for c in range(9)] for r in range(9)]
gc,_=call(A,"POST","/api/game/import",{"code":_share(_all_given)})
check("a grid claiming 81 clues is refused", gc, [400])

# Bounded decompression: 16KB of encoded input must not expand without limit.
_bomb = base64.urlsafe_b64encode(_gzip.compress(b"A" * (20 * 1024 * 1024))).rstrip(b"=").decode()
bc,_=call(A,"POST","/api/game/import",{"code":_bomb})
check("a gzip bomb is refused", bc, [400])

print("\n=== competitive boards are private over REST, not just WebSocket ===")
s,vb2=call(B,"POST","/api/daily/join")
vgid2=vb2.get("gameId") if isinstance(vb2,dict) else None
if vgid2:
    rc,_=call(A,"GET",f"/api/game/{vgid2}")
    check("attacker cannot READ another player's daily board", rc, [403])
    sc2,_=call(A,"GET",f"/api/game/{vgid2}/share")
    check("attacker cannot EXPORT another player's board", sc2, [403])
    oc,_=call(B,"GET",f"/api/game/{vgid2}")
    check("the owner can still read their own board", oc, [200])

print("\n=== FREEZE cannot target a stranger ===")
fc,_=call(A,"POST","/api/powerups/use/FREEZE?target=" + B["u"])
check("FREEZE on a non-opponent is refused", fc, [402,403,409])

print("\n=== unknown game ids map to 404, not 500 ===")
hc,_=call(A,"POST","/api/game/hint?gameId=definitely-not-a-real-game")
check("hint on an unknown game is 404", hc, [404])

print("\n=== un-friending a stranger does not provision a row ===")
dc,_=call(A,"DELETE","/api/friends/ghost-account-that-never-existed")
check("un-friending an unknown player is a harmless no-op", dc, [200,204,404])
duc,_=call(A,"POST","/api/duel/challenge",{"opponent":"ghost-account-that-never-existed"})
check("challenging a nonexistent player is refused", duc, [400,404])

bad=[r for r in results if not r[0]]
print(f"\n===== {len(results)-len(bad)}/{len(results)} passed, {len(bad)} FAILED =====")
for _,n,got,exp,note in bad: print(f"  FAIL {n}: got {got}, want {exp} {note}")
# Non-zero exit so CI treats a failed check as a failed build.
import sys as _sys; _sys.exit(1 if bad else 0)
