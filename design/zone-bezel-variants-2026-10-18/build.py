from pathlib import Path
import math
blue='#4D9FFF';bone='#EDE8DC';muted='#8F8B82'
def text(x,y,s,size=20,c=bone,w=500,extra=''):
 return f'<text x="{x}" y="{y}" fill="{c}" font-size="{size}" font-weight="{w}" text-anchor="middle" {extra}>{s}</text>'
def point(a,r=207):
 t=math.radians(a);return 216+r*math.cos(t),216+r*math.sin(t)
def arc(a,b,r=207):
 x,y=point(a,r);xx,yy=point(b,r);return f'M{x:.3f} {y:.3f} A{r} {r} 0 {int(b-a>180)} 1 {xx:.3f} {yy:.3f}'
# Five ordered zone strokes at the outside edge; the current second zone is blue.
colors=['#485054',blue,'#24543C','#60431C','#5F2923']
def strokes(angles,span,width=4,selectedWidth=6,activeSpan=None,neutral=False):
 s=''
 for i,a in enumerate(angles):
  d=(activeSpan if activeSpan is not None and i==1 else span)/2
  c=blue if i==1 else ('#303432' if neutral else colors[i])
  s+=f'<path d="{arc(a-d,a+d)}" fill="none" stroke="{c}" stroke-width="{selectedWidth if i==1 else width}" stroke-linecap="round"/>'
 return s
def radial_ticks():
 s=''
 for i,a in enumerate([138,114,90,66,42]):
  x,y=point(a,207);xx,yy=point(a,187 if i==1 else 198)
  s+=f'<path d="M{x:.3f} {y:.3f}L{xx:.3f} {yy:.3f}" stroke="{colors[i]}" stroke-width="{5 if i==1 else 3}" stroke-linecap="round"/>'
 return s
variants=[
 ('Q','넓게 펼친 호','심박 아래쪽 베젤에 다섯 선 · 현재 존만 굵게',strokes([138,114,90,66,42],10,3,6)),
 ('R','긴 현재 존 · 짧은 눈금','주변 선은 작게, 현재 존 선만 길게 강조',strokes([138,114,90,66,42],3,3,6,activeSpan=15)),
 ('S','방사형 눈금','베젤을 따라 선 대신 짧은 세로 눈금 배치',radial_ticks()),
 ('T','좁은 범위 · 단색','아래쪽으로 더 모으고 현재 존만 파랑',strokes([130,110,90,70,50],5,3,6,activeSpan=14,neutral=True)),
]
def face(z):
 return f'''<svg viewBox="0 0 432 432" role="img" aria-label="112 bpm Zone 2 베젤 시안" xmlns="http://www.w3.org/2000/svg">
<circle cx="216" cy="216" r="216" fill="#000"/>
{z}<path d="M205 26l11-8 11 8" fill="none" stroke="#A39F98" stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round"/>
{text(216,64,'02:05',26)}
<text x="99" y="166" fill="{blue}" font-size="112" font-weight="500" letter-spacing="-3">112</text><text x="293" y="166" fill="{muted}" font-size="22">bpm</text>
{text(216,205,'Zone 2',22,blue,500)}
{text(156,274,'32:10',40)}{text(291,274,'9',40)}
{text(156,303,'운동 시간',18,muted,400)}{text(291,303,'완료 세트',18,muted,400)}
</svg>'''
css='''*{box-sizing:border-box}body{margin:0;background:#121211;color:#EDE8DC;font-family:Roboto,'Noto Sans KR',sans-serif}main{max-width:880px;margin:auto;padding:36px 28px 48px}h1{font-size:26px;font-weight:600;letter-spacing:-1px;margin:0 0 8px}.intro{color:#A39F98;font-size:14px;line-height:1.6;margin:0 0 28px}.sheet{display:grid;grid-template-columns:1fr 1fr;gap:28px}svg{width:100%;display:block;font-family:Roboto,'Noto Sans KR',sans-serif}.variant{min-width:0}h2{font-size:17px;font-weight:500;margin:16px 0 6px}h2 span{font-size:13px;color:#8F8B82;margin-right:10px}.desc{color:#8F8B82;font-size:12px;line-height:1.6;margin:0}@media(max-width:560px){main{padding:24px 14px}.sheet{gap:24px 12px}h2{font-size:14px}.desc{font-size:11px}}'''
html='<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>워치짐 · 베젤 존 4안</title><link href="https://fonts.googleapis.com/css2?family=Noto+Sans+KR:wght@400;500;600;700&family=Roboto:wght@400;500;600;700&display=swap" rel="stylesheet"><style>'+css+'</style><main><h1>베젤 끝의 존 표시 · 4안</h1><p class="intro">Zone 2는 심박수 바로 아래에. 운동 시간과 완료 세트는 이전보다 50만큼 위로 모았어요.</p><section class="sheet" id="sheet-1">'
for code,title,desc,z in variants:html+=f'<article class="variant" id="variant-{code}">{face(z)}<h2><span>{code}</span>{title}</h2><p class="desc">{desc}</p></article>'
html+='</section></main></html>'
Path(__file__).with_name('index.html').write_text(html)
