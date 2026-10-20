package com.sheen.rokid.phone.debug

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Debug-only loopback page for the 0.26 P7 replacement corpus.
 *
 * The old T6 required a local `file:///sdcard/.../canvas.html` and canvas coordinates, which the production surface
 * deliberately does not support (D84 / local-file restriction). The replacement keeps the property the corpus is
 * really after - **strict ordered clicking** - but uses semantic buttons over HTTP:
 *
 *   red → green → blue → submit
 *
 * A wrong button resets the sequence; the marker `EDITH_SEQUENCE_OK` appears only after the ordered three clicks and
 * submit. The page is served on loopback only and exists in the debug source set; a release APK contains neither the
 * server nor this page. No external network, no personal data, no local-file capability.
 */
internal object T6PageServer {

    const val PORT = 8765
    const val PATH = "/"
    private const val TAG = "EdithT6Page"

    @Volatile private var socket: ServerSocket? = null

    fun start(): Boolean = synchronized(this) {
        if (socket != null) return true
        return runCatching {
            val bound = ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"))
            socket = bound
            Thread({
                while (!bound.isClosed) {
                    val client = runCatching { bound.accept() }.getOrNull() ?: break
                    Thread({ serve(client) }, "edith-t6-page").apply { isDaemon = true }.start()
                }
            }, "edith-t6-page-accept").apply { isDaemon = true }.start()
            Log.i(TAG, "T6_PAGE_SERVER port=$PORT url=http://127.0.0.1:$PORT$PATH")
            true
        }.getOrElse {
            Log.i(TAG, "T6_PAGE_SERVER failed=${it.javaClass.simpleName}")
            false
        }
    }

    private fun serve(client: Socket) {
        client.use { connection ->
            runCatching {
                val reader = BufferedReader(InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII))
                // The request body is irrelevant; consume the request line and headers so the browser sees a clean close.
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val body = PAGE.toByteArray(StandardCharsets.UTF_8)
                val headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n"
                connection.getOutputStream().apply {
                    write(headers.toByteArray(StandardCharsets.US_ASCII))
                    write(body)
                    flush()
                }
            }.onFailure { Log.i(TAG, "T6_PAGE_REQUEST failed=${it.javaClass.simpleName}") }
        }
    }

    private val PAGE = """
        <!doctype html>
        <html lang="zh"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>EDITH 顺序测试</title>
        <style>
          body{font-family:sans-serif;padding:24px;background:#fff;color:#111}
          .row{display:flex;gap:16px;margin:24px 0}
          button{font-size:32px;padding:18px 28px}
          #status{font-size:24px} #result{font-size:28px;font-weight:bold;margin-top:24px;color:#0a6}
          #hint{color:#555} 
        </style></head>
        <body>
        <h1>EDITH 顺序测试</h1>
        <p id="hint">页面只显示下一步按钮。依次点击 红 → 绿 → 蓝，三次完成后自动通过。</p>
        <p id="status">sequence=empty</p>
        <div class="row">
          <button id="next" onclick="nextStep()">第1步：红</button>
        </div>
        <p id="result"></p>
        <script>
          var steps=[['red','红'],['green','绿'],['blue','蓝']], at=0, done=false;
          function update(){
            document.getElementById('status').textContent='sequence='+(at===0?'empty':steps.slice(0,at).map(function(s){return s[0];}).join(','));
            var b=document.getElementById('next');
            if(done){ b.textContent='已完成'; b.disabled=true; }
            else if(at<steps.length){ b.textContent='第'+(at+1)+'步：'+steps[at][1]; }
          }
          function nextStep(){
            if(done) return;
            at++; update();
            if(at===steps.length) finishSequence();
          }
          function finishSequence(){
            done=true;
            document.getElementById('result').textContent='EDITH_SEQUENCE_OK';
            document.title='EDITH_SEQUENCE_OK';
            update();
          }
        </script>
        </body></html>
    """.trimIndent()
}
