package dev.dsh.mobile.plugin.html.diagram

/**
 * 每种 kind 的整页 HTML 生成器：纯 JVM，没有 Android 依赖，可以直接单测。
 *
 * 源码一律 HTML 转义后放进 hidden textarea，驱动脚本只读它的 .value —— 这样 </script>
 * 引号、反斜杠都只是文本，不会提前闭合标签、也不会跑出字符串，不需要维护第二套 JS 转义。
 *
 * 每页只有三段脚本：离线库 script src、通用工具（S()/F()/D()/FIT()）、该 kind 的驱动。
 * 高度上报走 DSH_BRIDGE.postMessage（只传 reason，不传内容），宿主收到后自己量高度。
 */
internal object DiagramHtml {

    // 资源名 = src/main/resources/diagram/ 下的文件名；DiagramAssets 按 ASSETS 复制到 cacheDir。
    const val LIB_MERMAID = "mermaid.min.js"
    const val LIB_ECHARTS = "echarts.min.js"
    const val LIB_CHART = "chart.umd.min.js"
    const val LIB_VIZ3 = "viz-global.js"
    const val LIB_VIZ2 = "viz.js"
    const val LIB_VIZ2_RENDER = "full.render.js"

    /** 全部离线库；复制顺序不影响使用（每页只引自己那一两个）。 */
    val ASSETS = listOf(LIB_VIZ3, LIB_VIZ2, LIB_VIZ2_RENDER, LIB_MERMAID, LIB_ECHARTS, LIB_CHART)

    enum class Mode { SVG, MERMAID, GRAPHVIZ, ECHARTS, CHARTJS, HTML, PLANTUML, SOURCE }

    /** kind 归一后再查表；dot 是 graphviz 的常见别名，宿主未必归一。 */
    fun modeOf(kind: String): Mode = when (kind.trim().lowercase()) {
        "svg" -> Mode.SVG
        "mermaid" -> Mode.MERMAID
        "graphviz", "dot" -> Mode.GRAPHVIZ
        "echarts" -> Mode.ECHARTS
        "chartjs", "chart.js" -> Mode.CHARTJS
        "html" -> Mode.HTML
        "plantuml" -> Mode.PLANTUML
        else -> Mode.SOURCE // ascii / tikz / python / 未知识别类型
    }

    /** 源码卡上那一行说明；WebView 类 kind 用不到。 */
    fun noteOf(kind: String): String = when (kind.trim().lowercase()) {
        "ascii" -> "等宽文本，长行可横向滚动查看"
        "tikz" -> "TikZ 需要本地 LaTeX 引擎，这里只显示源码"
        "python" -> "Python 绘图需在终端/PC 运行，这里只显示源码"
        else -> "暂不支持渲染该类型，显示源码"
    }

    /** 只转 5 个字符；浏览器解析 textarea 时会解回原文，所以是精确可逆的。 */
    fun escapeHtml(value: String): String {
        val out = StringBuilder(value.length + 16)
        for (ch in value) {
            when (ch) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&#39;")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    /**
     * 生成整页 HTML。未知 kind 也返回一页只显示源码的文档 —— 宁可降级成源码，也不给白屏。
     * dark 决定库主题与底色（mermaid theme、echarts theme、正文前景色）。
     */
    fun build(kind: String, source: String, dark: Boolean): String {
        val mode = modeOf(kind)
        val lib = when (mode) {
            Mode.MERMAID -> LIB_MERMAID
            Mode.GRAPHVIZ -> LIB_VIZ3
            Mode.ECHARTS -> LIB_ECHARTS
            Mode.CHARTJS -> LIB_CHART
            else -> null
        }
        val driver = when (mode) {
            Mode.SVG -> DRIVER_SVG
            Mode.MERMAID -> DRIVER_MERMAID
            Mode.GRAPHVIZ -> DRIVER_GRAPHVIZ
            Mode.ECHARTS -> DRIVER_ECHARTS
            Mode.CHARTJS -> DRIVER_CHARTJS
            else -> DRIVER_FALLBACK
        }
        val bg = if (dark) "#17181c" else "#ffffff"
        val fg = if (dark) "#e8e8ea" else "#1b1b1f"
        return buildString(source.length + 4096) {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<style>")
            append("html,body{margin:0;padding:0;overflow:hidden;background:transparent;color:")
            append(fg).append(";-webkit-text-size-adjust:100%;}")
            append("#wrap{padding:2px 4px;}")
            append("#err{display:none;color:#ff6b6b;font:12px ui-monospace,monospace;white-space:pre-wrap;padding:6px;}")
            when (mode) {
                Mode.SVG -> append("#out{width:100%;}#out svg{display:block;width:100%;height:auto;}")
                Mode.MERMAID -> append("html,body{background:").append(bg)
                    .append(";}#out{width:100%;padding:8px;box-sizing:border-box;}#out svg{max-width:100%;height:auto;}")
                // graphviz 自己就是白底黑字，硬套深色主题会变成白字白底：给它一块固定白面板
                Mode.GRAPHVIZ -> append("#out{width:100%;box-sizing:border-box;padding:6px;background:#ffffff;border-radius:10px;}")
                    .append("#out svg{display:block;width:100%;height:auto;}")
                Mode.ECHARTS, Mode.CHARTJS -> append("html,body{background:").append(bg)
                    .append(";}#out{width:100%;height:260px;}")
                else -> append("#out{width:100%;}#out pre{margin:0;font:12px ui-monospace,monospace;white-space:pre;overflow:auto;color:")
                    .append(fg).append(";}")
            }
            append("</style></head><body>")
            append("<textarea id=\"src\" hidden>").append(escapeHtml(source)).append("</textarea>")
            append("<div id=\"wrap\"><div id=\"out\"></div><div id=\"err\"></div></div>")
            if (lib != null) append("<script src=\"").append(lib).append("\"></script>")
            append("<script>var DARK=").append(if (dark) "true" else "false").append(";").append(COMMON).append("</script>")
            append("<script>").append(driver).append("</script>")
            append("<script>").append(BRIDGE).append("</script>")
            append("</body></html>")
        }
    }

    // ---- 通用工具：S=源码 / O=输出容器 / F=错误卡 / D=上报高度 / FIT=SVG 自适应 ----
    private val COMMON = """
        var S=function(){var e=document.getElementById('src');return e?e.value:'';};
        var O=function(){return document.getElementById('out');};
        var F=function(m){var d=document.getElementById('err');if(!d)return;
          d.style.display='block';d.textContent='渲染失败：'+((m&&m.message)?m.message:String(m));};
        var D=function(){try{if(window.DSH_BRIDGE)window.DSH_BRIDGE.postMessage('rendered');}catch(e){}};
        var FIT=function(sv){
          if(!sv)return;
          if(!sv.getAttribute('viewBox')){
            var w=parseFloat(sv.getAttribute('width')),h=parseFloat(sv.getAttribute('height'));
            if(w&&h)sv.setAttribute('viewBox','0 0 '+w+' '+h);
          }
          sv.removeAttribute('height');sv.removeAttribute('width');
          sv.style.display='block';sv.style.width='100%';sv.style.maxWidth='100%';sv.style.height='auto';
        };
    """.trimIndent()

    private val BRIDGE = """
        window.DSH={notify:function(w){try{if(window.DSH_BRIDGE)window.DSH_BRIDGE.postMessage(w);}catch(e){}}};
        window.addEventListener('load',function(){D();});
        if(document.fonts&&document.fonts.ready){document.fonts.ready.then(function(){D();});}
    """.trimIndent()

    /** 内联 SVG：DOMParser 解析，去掉 script/on*，丢掉固定宽高让 CSS 按容器缩放。 */
    private val DRIVER_SVG = """
        (function(){
          try{
            var s=S().trim();
            var doc=new DOMParser().parseFromString(s,'image/svg+xml');
            if(doc.getElementsByTagName('parsererror').length)throw new Error('SVG 解析失败');
            var svg=doc.documentElement;
            if(!svg||svg.nodeName.toLowerCase()!=='svg')throw new Error('根节点不是 svg');
            var kill=svg.querySelectorAll('script,foreignObject');
            for(var i=kill.length-1;i>=0;i--){kill[i].parentNode.removeChild(kill[i]);}
            var all=svg.querySelectorAll('*');
            for(var j=0;j<all.length;j++){
              var el=all[j];
              for(var k=el.attributes.length-1;k>=0;k--){
                if(/^on/i.test(el.attributes[k].name))el.removeAttribute(el.attributes[k].name);
              }
            }
            FIT(svg);
            O().appendChild(document.importNode(svg,true));
          }catch(e){F(e);}
          D();
        })();
    """.trimIndent()

    private val DRIVER_MERMAID = """
        (function(){
          var run=async function(){
            try{
              var m=window.mermaid;
              if(!m)throw new Error('mermaid.min.js 未加载');
              m.initialize({startOnLoad:false,securityLevel:'strict',theme:DARK?'dark':'default',
                fontFamily:'system-ui,-apple-system,sans-serif'});
              var box=document.createElement('div');
              box.className='mermaid';
              box.textContent=S().trim();
              O().appendChild(box);
              await m.run({nodes:[box]});
              var svg=O().querySelector('svg');
              if(!svg)throw new Error('mermaid 没有产出 SVG');
              FIT(svg);
            }catch(e){F(e);}
            D();
          };
          run();
        })();
    """.trimIndent()

    /** graphviz：先 viz 3.x（viz-global.js，单文件、不需要 Worker），失败退 viz.js 2.1.2 + full.render.js。 */
    private val DRIVER_GRAPHVIZ = """
        (function(){
          var src=S().trim();
          var V3=window.Viz;
          var ok=function(el){O().appendChild(el);FIT(el);D();};
          var bad=function(e){F(e);D();};
          var load=function(u){
            return new Promise(function(res,rej){
              var s=document.createElement('script');
              s.src=u;s.onload=function(){res();};
              s.onerror=function(){rej(new Error('加载失败 '+u));};
              document.head.appendChild(s);
            });
          };
          var v3=function(){
            if(!V3||typeof V3.instance!=='function')return Promise.reject(new Error('viz-global.js 不可用'));
            return V3.instance().then(function(v){return v.renderSVGElement(src);});
          };
          // 2.x 走 ModuleWrapper（不进 Worker）：file:// 下 new Worker 常被拦，full.render.js 就是那条同步路径
          var v2=function(){
            return load('viz.js').then(function(){return load('full.render.js');}).then(function(){
              var V=window.Viz;
              if(typeof V!=='function')throw new Error('viz.js 未定义 Viz');
              return new V().renderSVGElement(src);
            });
          };
          v3().then(ok).catch(function(e1){
            v2().then(ok).catch(function(e2){bad(new Error(e1.message+' / '+e2.message));});
          });
        })();
    """.trimIndent()

    private val DRIVER_ECHARTS = """
        (function(){
          try{
            if(!window.echarts)throw new Error('echarts.min.js 未加载');
            var s=S().trim(),opt;
            try{opt=JSON.parse(s);}catch(e){opt=new Function('return ('+s+');')();}
            if(!opt||typeof opt!=='object')throw new Error('option 不是对象/JSON');
            var chart=echarts.init(O(),DARK?'dark':null,{renderer:'canvas'});
            chart.setOption(opt);
            window.__dshChart=chart;
            window.addEventListener('resize',function(){try{chart.resize();}catch(e){}});
            setTimeout(function(){try{chart.resize();}catch(e){}D();},80);
          }catch(e){F(e);D();}
        })();
    """.trimIndent()

    private val DRIVER_CHARTJS = """
        (function(){
          try{
            if(!window.Chart)throw new Error('chart.umd.min.js 未加载');
            var s=S().trim(),cfg;
            try{cfg=JSON.parse(s);}catch(e){cfg=new Function('return ('+s+');')();}
            if(!cfg||typeof cfg!=='object')throw new Error('config 不是对象/JSON');
            Chart.defaults.color=DARK?'#d8d8dc':'#333';
            Chart.defaults.borderColor=DARK?'rgba(255,255,255,.15)':'rgba(0,0,0,.12)';
            var cv=document.createElement('canvas');
            cv.style.maxWidth='100%';cv.style.maxHeight='100%';
            O().appendChild(cv);
            new Chart(cv.getContext('2d'),cfg);
            setTimeout(D,80);
          }catch(e){F(e);D();}
        })();
    """.trimIndent()

    /** 未知 kind / 纯文本类：等宽显示源码，长行不折。 */
    private val DRIVER_FALLBACK = """
        (function(){
          try{
            var pre=document.createElement('pre');
            pre.textContent=S();
            O().appendChild(pre);
          }catch(e){F(e);}
          D();
        })();
    """.trimIndent()
}
