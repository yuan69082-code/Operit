package com.ai.assistance.operit.core.experience

import org.json.JSONObject

/** Installed before project scripts so render failures also become inspectable evidence. */
object PreviewInspectionScript {
    fun create(saved: JSONObject): String {
        val state = JSONObject.quote(saved.toString()).replace("<", "\\u003c")
        return """
(function(){
 const saved=JSON.parse($state), errors=[];
 const record=(message)=>{errors.push(String(message).slice(0,1500));if(errors.length>30)errors.shift();};
 window.addEventListener('error',event=>record(event.message||('资源加载失败: '+(event.target.src||event.target.href||event.target.tagName))),true);
 window.addEventListener('unhandledrejection',event=>record(event.reason));
 try {
  localStorage.clear();sessionStorage.clear();
  for(const [key,value] of Object.entries(saved.local||{}))localStorage.setItem(key,value);
  for(const [key,value] of Object.entries(saved.session||{}))sessionStorage.setItem(key,value);
 } catch(error){record(error);}
 const fields=()=>Array.from(document.querySelectorAll('input,textarea,select'));
 window.addEventListener('DOMContentLoaded',()=>{
  fields().forEach((element,index)=>{const prior=(saved.forms||[])[index];if(prior&&element.type!=='password'&&element.type!=='file'){element.value=prior.value;element.checked=prior.checked;}});
  const save=()=>{
   try {
    const elements=Array.from(document.querySelectorAll('body,main,header,nav,section,article,button,input,textarea,select,a,img,canvas,[data-inspect]'));
    const nodes=elements.slice(0,120).map(element=>{
     const style=getComputedStyle(element),bounds=element.getBoundingClientRect();
     return {tag:element.tagName,id:element.id,classes:element.className.toString(),boundsPx:[bounds.x,bounds.y,bounds.width,bounds.height],
      visible:style.display!=='none'&&style.visibility!=='hidden'&&bounds.width>0&&bounds.height>0,
      color:style.color,background:style.backgroundColor,backgroundImage:style.backgroundImage,opacity:style.opacity,
      fontFamily:style.fontFamily,fontSize:style.fontSize,fontWeight:style.fontWeight,lineHeight:style.lineHeight,
      borderRadius:style.borderRadius,border:style.border,boxShadow:style.boxShadow,overflow:style.overflow};
    });
    const result={local:{...localStorage},session:{...sessionStorage},forms:fields().map(element=>({value:['password','file'].includes(element.type)?'':element.value,checked:element.checked})),
      text:document.body.innerText.slice(0,20000),url:location.href,
      render:{capturedAt:Date.now(),viewport:{width:innerWidth,height:innerHeight,devicePixelRatio},nodes,totalNodes:elements.length,truncated:elements.length>nodes.length,errors:[...errors]}};
    OperitPreview.save(JSON.stringify(result));return result.render;
   }catch(error){record(error);console.error(error);return {error:String(error)};}
  };
  window.__operitPersistPreview=save;document.addEventListener('input',save);document.addEventListener('change',save);setInterval(save,2000);save();
 });
})();
""".trimIndent()
    }
}
