from pathlib import Path
blue='#4D9FFF'; bone='#EDE8DC'
def text(x,y,s,size=20,fill=bone,weight=500,extra=''):
 return f'<text x="{x}" y="{y}" fill="{fill}" font-size="{size}" font-weight="{weight}" text-anchor="middle" {extra}>{s}</text>'
def rect(x,y,w,h,c,r=0): return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{c}"/>'
def line(x,y,x2,y2,c,w=2): return f'<path d="M{x} {y}H{x2}" stroke="{c}" stroke-width="{w}"/>' if y==y2 else f'<path d="M{x} {y}L{x2} {y2}" stroke="{c}" stroke-width="{w}"/>'
def ticks(xs,style='v'):
 out=''
 for x,c in zip(xs,['#303638','#183B2B','#493312','#451D1A']):
  if style=='v': out+=rect(x-2,210,4,12,c,1)
  elif style=='sq': out+=rect(x-3,213,6,6,c,1)
  elif style=='dot': out+=f'<circle cx="{x}" cy="216" r="3" fill="{c}"/>'
 return out
variants=[]
variants.append(('A','세로 틱','채운 면 없이 글자와 얇은 기준선만',ticks([128,304,320,336])+text(216,222,'ZONE 2',22,blue,500,extra='letter-spacing="1.2"')+rect(178,235,76,3,blue,1)))
variants.append(('B','작은 사각형','낮고 각진 바 · 부드러운 대소문자',ticks([134,298,314,330],'sq')+rect(164,198,104,36,blue,2)+text(216,222,'Zone 2',21,'#051629',600)))
variants.append(('C','한글 · 열린 바','존 이름을 짧게, 색 면은 한쪽에만',ticks([132,300,316,332])+rect(169,202,3,28,blue,1)+text(223,224,'존 2',25,blue,500)))
variants.append(('D','숫자 중심','작은 점들 사이에 존 번호를 크게',ticks([139,293,309,325],'dot')+text(201,222,'ZONE',13,'#A39F98',500,extra='letter-spacing="1.3"')+text(240,226,'2',32,blue,500)+rect(181,237,70,2,blue)))
e=ticks([134,298,314,330]);e+=text(216,207,'Zone 2',21,blue,500)
for x,h in [(190,10),(201,17),(212,23),(223,17),(234,10)]:e+=rect(x,238-h,5,h,blue,1)
variants.append(('E','리듬 막대','존 표시는 위에, 강조는 세로 막대 묶음',e))
variants.append(('F','열린 프레임','큰 색 면 대신 네 귀퉁이만 남긴 형태',ticks([132,300,316,332],'sq')+f'<path d="M177 207v-8h12 M243 199h12v8 M255 225v8h-12 M189 233h-12v-8" fill="none" stroke="{blue}" stroke-width="2"/>'+text(216,224,'Z2',26,blue,500,extra='letter-spacing="1"')))
variants.append(('G','가는 레일','문구가 얇은 파란 선을 끊고 들어가는 형태',ticks([122,310,326,342])+line(153,216,279,216,blue,2)+rect(174,198,84,36,'#000')+text(216,222,'ZONE 2',18,blue,500,extra='letter-spacing=".8"')))
variants.append(('H','번호 타일','존 번호만 작은 색 면에, 이름은 가볍게',ticks([128,304,320,336],'dot')+rect(174,198,36,36,blue,2)+text(192,225,'2',27,'#051629',500)+text(240,221,'ZONE',15,'#A39F98',500,extra='letter-spacing="1.2"')))
def face(z):
 return f'''<svg viewBox="0 0 432 432" role="img" aria-label="112 bpm, 심박 존 2 시안" xmlns="http://www.w3.org/2000/svg"><circle cx="216" cy="216" r="216" fill="#000"/>
<path d="M205 26l11-8 11 8" fill="none" stroke="#A39F98" stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round"/>
{text(216,64,'02:05',26)}
<text x="99" y="166" fill="{blue}" font-size="112" font-weight="500" letter-spacing="-3">112</text><text x="293" y="166" fill="#8F8B82" font-size="22">bpm</text>
{z}
{text(156,324,'32:10',40)}{text(291,324,'9',40)}
{text(156,353,'운동 시간',18,'#8F8B82',400)}{text(291,353,'완료 세트',18,'#8F8B82',400)}</svg>'''
css='''*{box-sizing:border-box}body{margin:0;background:#121211;color:#EDE8DC;font-family:Roboto,'Noto Sans KR',sans-serif}main{max-width:880px;margin:0 auto;padding:36px 28px 48px}h1{font-size:26px;letter-spacing:-1px;font-weight:600;margin:0 0 8px}p.intro{font-size:14px;color:#A39F98;margin:0 0 28px;line-height:1.6}.sheet{display:grid;grid-template-columns:1fr 1fr;gap:28px 28px;margin-bottom:32px}.variant{min-width:0}svg{display:block;width:100%;font-family:Roboto,'Noto Sans KR',sans-serif}h2{font-size:17px;margin:16px 0 6px;font-weight:500}h2 span{color:#8F8B82;font-size:13px;margin-right:10px}p.desc{font-size:12px;color:#8F8B82;margin:0;line-height:1.6}.variant:focus-within svg{outline:1px solid #4D9FFF}@media(max-width:560px){main{padding:24px 14px}.sheet{gap:24px 12px}h2{font-size:14px}p.desc{font-size:11px}}'''
html='<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>워치짐 · 심박 존 8안</title><link href="https://fonts.googleapis.com/css2?family=Noto+Sans+KR:wght@400;500;600;700&family=Roboto:wght@400;500;600;700&display=swap" rel="stylesheet"><style>'+css+'</style><main><h1>심박 존 · 8가지 방향</h1><p class="intro">현재 존은 중앙에. 같은 심박수와 화면에서 글자 · 강조 면 · 주변 표식을 비교해요.</p>'
for n in range(2):
 html+=f'<section class="sheet" id="sheet-{n+1}">'
 for code,title,desc,z in variants[n*4:n*4+4]:html+=f'<article class="variant" id="variant-{code}">{face(z)}<h2><span>{code}</span>{title}</h2><p class="desc">{desc}</p></article>'
 html+='</section>'
html+='</main></html>'
Path(__file__).with_name('index.html').write_text(html)
