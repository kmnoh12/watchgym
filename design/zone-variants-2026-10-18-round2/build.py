from pathlib import Path
import runpy
base=Path(__file__).resolve().parent.parent/'zone-variants-2026-10-18/build.py'
# Reuse only the unchanged watch face and rendering primitives.
n=runpy.run_path(str(base)); text=n['text'];rect=n['rect'];line=n['line'];face=n['face'];css=n['css']
b='#4D9FFF'; white='#EDE8DC'; dim=['#303638','#183B2B','#493312','#451D1A']
def marks(xs,kind,y=216):
 s=''
 for x,c in zip(xs,dim):
  if kind=='diamond':s+=f'<path d="M{x} {y-4}l4 4-4 4-4-4z" fill="{c}"/>'
  elif kind=='ring':s+=f'<circle cx="{x}" cy="{y}" r="3.5" fill="none" stroke="{c}" stroke-width="2"/>'
  elif kind=='tall':s+=rect(x-1.5,y-14,3,28,c,1)
  elif kind=='dash':s+=rect(x-6,y-1.5,12,3,c,1)
  elif kind=='square':s+=rect(x-3,y-3,6,6,c)
 return s
v=[]
z=marks([130,302,320,338],'diamond')+f'<path d="M166 198h100l10 18-10 18H166l-10-18z" fill="{b}"/>'+text(216,223,'ZONE 2',20,'#061522',600,extra='letter-spacing=".7"')
v.append(('I','커팅 리본','사선 끝을 가진 얇은 면 · 주변은 마름모',z))
z=marks([132,300,318,336],'ring')+f'<rect x="158" y="197" width="116" height="38" rx="19" fill="none" stroke="{b}" stroke-width="1.5"/>'+text(216,223,'Zone 2',22,b,500)
v.append(('J','아웃라인 캡슐','속은 검정 그대로 · 외곽선과 원형 표식',z))
z=text(216,210,'ZONE 2',20,white,500,extra='letter-spacing="1.1"')
for x,w,c in [(125,27,dim[0]),(157,118,b),(280,17,dim[1]),(302,17,dim[2]),(324,17,dim[3])]:z+=rect(x,227,w,5,c,1)
v.append(('K','넓은 진행 띠','흰 글자 아래 긴 현재 구간 · 낮은 수평 띠',z))
z=marks([129,303,321,339],'tall')+rect(158,196,116,40,'#10253C',3)+text(216,223,'Zone 2',22,b,500)
v.append(('L','낮은 대비','채도를 낮춘 면 · 나머지 존은 긴 세로선',z))
z=text(216,215,'Zone 2',23,b,500)+f'<path d="M211 226h10l-5 6z" fill="{b}"/>'
for x,c in [(188,dim[0]),(216,b),(244,dim[1]),(272,dim[2]),(300,dim[3])]:z+=rect(x-4,240,8,8,c,1)
v.append(('M','포인터 · 다섯 점','글자 아래 작은 포인터로 선택 위치를 짚는 방식',z))
z=text(216,214,'ZONE 2',20,b,500,extra='letter-spacing="1"')
# Five understated segments on a shallow arc; the second zone is directly under the centreline.
for d,c,w in [('M170 231q9 5 18 7',dim[0],3),('M198 240q18 3 36 0',b,5),('M244 238q10-2 19-7',dim[1],3),('M272 226q8-5 14-11',dim[2],3),('M293 206q5-6 8-12',dim[3],3)]:z+=f'<path d="{d}" fill="none" stroke="{c}" stroke-width="{w}" stroke-linecap="round"/>'
v.append(('N','얕은 아크','직선 바 대신 짧게 나눈 곡선으로 구간 표시',z))
z=marks([128,304,322,340],'square')
# Small matrix is contained in the selected field, away from its complete label.
for x in [155,161]:
 for y in [206,213,220]:z+=rect(x,y,3,3,b,0.5)
z+=text(223,224,'ZONE 2',22,white,500,extra='letter-spacing=".4"')
v.append(('O','매트릭스','글자는 밝게, 현재 색은 작은 점 배열로만',z))
z=marks([127,305,325,345],'dash')+text(216,224,'Zone 2',24,white,500)+f'<path d="M156 200v32 M276 200v32" stroke="{b}" stroke-width="3"/>'
v.append(('P','사이드 레일','바탕 없이 양쪽 세로선으로 현재 존을 감싸기',z))
assert len(v)==8
for _,_,_,z in v:
 assert 'ZONE 2' in z or 'Zone 2' in z
html='<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>워치짐 · 심박 존 추가 8안</title><link href="https://fonts.googleapis.com/css2?family=Noto+Sans+KR:wght@400;500;600;700&family=Roboto:wght@400;500;600;700&display=swap" rel="stylesheet"><style>'+css+'</style><main><h1>심박 존 · 추가 8안</h1><p class="intro">표기는 Zone 2 / ZONE 2. 현재 존을 중앙에 두고, 형태와 표식만 비교해요.</p>'
for k in range(2):
 html+=f'<section class="sheet" id="sheet-{k+1}">'
 for code,title,desc,z in v[k*4:k*4+4]:html+=f'<article class="variant" id="variant-{code}">{face(z)}<h2><span>{code}</span>{title}</h2><p class="desc">{desc}</p></article>'
 html+='</section>'
html+='</main></html>'
Path(__file__).with_name('index.html').write_text(html)
