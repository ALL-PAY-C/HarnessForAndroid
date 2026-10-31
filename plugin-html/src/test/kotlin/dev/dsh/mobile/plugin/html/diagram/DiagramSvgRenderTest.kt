package dev.dsh.mobile.plugin.html.diagram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 真渲染断言，不是字符串断言。
 *
 * 背景：SVG 卡出现过「卡片高度对、内容区一个像素都不画」的回归 —— CSS 里该有的规则一条不少，
 * 只断言字符串的单测全绿，图就是不出现（内层 <svg> 在 WebView 里高度塌成 0）。
 * 所以这里把 [DiagramHtml.build] 生成的真页面丢进 headless Chromium，让 Blink 真排一遍版，
 * 再从页面里量回几何：
 * - 根 <svg> 的 getBoundingClientRect 宽高都 > 0，且高 = 宽 × viewBox 比例；
 * - rect / circle / text 各自的渲染盒子都 > 0（内容真的被布局出来，不是空 svg）；
 * - text 底边落在 <svg> 盒子内（没被裁）；
 * - elementFromPoint 在矩形中心命中的是那个 rect（该点确实有内容，不是空白/被遮）。
 *
 * 机器上没有 Chromium 时 assume 跳过，免得分环境假通过或假失败。
 */
class DiagramSvgRenderTest {

    private val sample = """
        <svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 240 120'>
          <rect x='10' y='10' width='100' height='60' rx='8' fill='#2563eb'/>
          <circle cx='180' cy='40' r='30' fill='#22c55e'/>
          <text x='30' y='100' font-size='16' fill='#111111'>SVG 真图测试</text>
        </svg>
    """.trimIndent()

    /** 视口给足高度（400x400），FITPX 就不会把高夹到容器，宽高比可以严格断言。 */
    private val viewport = "400,400"

    private val chromeBinary: File? = listOf(
        System.getenv("CHROME_BIN").orEmpty(),
        "C:/Program Files/Google/Chrome/Application/chrome.exe",
        "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
        "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
        "/usr/bin/google-chrome",
        "/usr/bin/chromium",
    ).filter { it.isNotBlank() }.map(::File).firstOrNull { it.isFile && it.canExecute() }

    @Test
    fun svg_content_is_laid_out_inside_the_card() {
        Assume.assumeTrue("没有可用的 Chromium，跳过真渲染断言", chromeBinary != null)
        val chrome = chromeBinary!!

        val dir = File(System.getProperty("java.io.tmpdir"), "dsh-svg-render").apply { mkdirs() }
        val page = File(dir, "page.html").apply {
            writeText(DiagramHtml.build("svg", sample, dark = false).replace("</body>", PROBE + "</body>"))
        }
        val url = "file:///" + page.absolutePath.replace('\\', '/')

        val dom = run(chrome, "--dump-dom", "--window-size=" + viewport, url)
        val probe = Regex("data-probe=\"([^\"]*)\"").find(dom)?.groupValues?.get(1)
        assertTrue("探针没跑出来，dump-dom 里没有 data-probe：\n" + dom.take(2000), probe != null)

        val m = probe!!.split(';').mapNotNull {
            val i = it.indexOf('=')
            if (i > 0) it.substring(0, i) to it.substring(i + 1) else null
        }.toMap()
        fun num(k: String): Double = m[k]?.toDoubleOrNull() ?: Double.NaN

        val svgW = num("svgW")
        val svgH = num("svgH")
        val svgBottom = num("svgBottom")
        val bboxH = num("bboxH")
        val rectW = num("rectW")
        val rectH = num("rectH")
        val circleW = num("circleW")
        val textH = num("textH")
        val textBottom = num("textBottom")
        val hit = m["hit"].orEmpty()

        // 根 <svg> 必须真的占了一块非零的像素区域
        assertTrue("根 <svg> 渲染宽高必须都 > 0，实际 " + svgW + "x" + svgH, svgW > 0 && svgH > 0)
        // 且严格等比：viewBox 240x120 → 高 = 宽/2
        val delta = svgH - svgW / 2
        assertTrue("viewBox 240x120 要求高=宽/2，实际 " + svgW + "x" + svgH, delta >= -2.0 && delta <= 2.0)
        // 内容不是空的：内容包围盒、三个图元的渲染盒子都得有尺寸
        assertTrue("svg 内容自身高度必须 > 0（getBBox），实际 " + bboxH, bboxH > 0)
        assertTrue("蓝矩形没被布局出来，渲染盒子 " + rectW + "x" + rectH, rectW > 0 && rectH > 0)
        assertTrue("绿圆没被布局出来，渲染盒子宽 " + circleW, circleW > 0)
        assertTrue("文字没被布局出来，渲染盒子高 " + textH, textH > 0)
        // 没被卡片底边裁掉
        assertTrue("text 底边 " + textBottom + " 超出 <svg> 底边 " + svgBottom, textBottom <= svgBottom + 0.5)
        // 矩形中心那个点确实压着 rect（排除「盒子有尺寸但内容不画」）
        assertEquals("矩形中心命中的应该是 rect，实际 " + hit, "rect", hit)
    }

    private fun run(chrome: File, vararg args: String): String {
        val cmd = mutableListOf(
            chrome.absolutePath,
            "--headless=new",
            "--disable-gpu",
            "--no-sandbox",
            "--hide-scrollbars",
            "--force-device-scale-factor=1",
            "--virtual-time-budget=1500",
        ).apply { addAll(args) }
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(120, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw AssertionError("Chromium 超时：" + cmd)
        }
        return out
    }

    private companion object {
        /** 页面加载完量一次，写进 <html data-probe>，dump-dom 直接读回来。 */
        val PROBE = """
            <script>
            (function(){
              var r1=function(v){return Math.round(v*10)/10;};
              try{
                var s=document.querySelector('#out svg');
                if(!s)throw new Error('页面上没有 #out svg');
                var box=function(el){var r=el.getBoundingClientRect();return r;};
                var r=box(s),b=s.getBBox();
                var rect=s.querySelector('rect'),circ=s.querySelector('circle'),txt=s.querySelector('text');
                var rr=box(rect),cr=box(circ),tr=box(txt);
                var hit=document.elementFromPoint(rr.left+rr.width/2,rr.top+rr.height/2);
                document.documentElement.setAttribute('data-probe',
                  'svgW='+r1(r.width)+';svgH='+r1(r.height)+';svgBottom='+r1(r.bottom)+
                  ';bboxH='+r1(b.height)+
                  ';rectW='+r1(rr.width)+';rectH='+r1(rr.height)+';circleW='+r1(cr.width)+
                  ';textH='+r1(tr.height)+';textBottom='+r1(tr.bottom)+
                  ';hit='+(hit?hit.tagName.toLowerCase():'none')+
                  ';vw='+window.innerWidth+';vh='+window.innerHeight);
              }catch(e){
                document.documentElement.setAttribute('data-probe','svgW=NaN;svgH=NaN;err='+
                  String(e.message).replace(/;/g,','));
              }
            })();
            </script>
        """.trimIndent()
    }
}
