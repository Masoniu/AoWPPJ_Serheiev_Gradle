import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;

/**
 * @author Serheiev Maksym
 */
public class WebServer {
    private final HybridEngine engine;

    public WebServer(HybridEngine engine) {
        this.engine = engine;
    }

    public void start() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", exchange -> {
            String html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Local Shazam</title>
                    <style>
                        body { font-family: 'Segoe UI', sans-serif; text-align: center; background-color: #1a1a2e; color: white; padding-top: 100px; }
                        button { padding: 25px 50px; font-size: 24px; cursor: pointer; border-radius: 50px; border: none; background-color: #e94560; color: white; transition: 0.3s; font-weight: bold;}
                        button:active { background-color: #0f3460; }
                        button.recording { animation: pulse 1.5s infinite; background-color: #e94560; }
                        @keyframes pulse { 0% { transform: scale(1); box-shadow: 0 0 20px #e94560;} 50% { transform: scale(1.05); box-shadow: 0 0 40px #e94560;} 100% { transform: scale(1); box-shadow: 0 0 20px #e94560;} }
                        #result { margin-top: 40px; font-size: 32px; font-weight: bold; color: #4ecca3; }
                        .loader { font-size: 20px; color: #aaaaaa; }
                    </style>
                </head>
                <body>
                    <h1>Music Information Retrieval</h1>
                    <p style="color: #aaaaaa; margin-bottom: 40px;">Press the button and sing or play the music</p>
                    <button id="recordBtn">Start Listening</button>
                    <div id="result">Ready for input...</div>
            
                    <script>
                        let mediaRecorder;
                        let audioChunks = [];
                        const btn = document.getElementById('recordBtn');
                        const res = document.getElementById('result');
            
                        btn.onclick = async () => {
                            if (mediaRecorder && mediaRecorder.state === 'recording') return;
                            try {
                                const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
                                mediaRecorder = new MediaRecorder(stream);
                                audioChunks = [];
                                
                                mediaRecorder.ondataavailable = e => audioChunks.push(e.data);
                                mediaRecorder.onstop = async () => {
                                    const audioBlob = new Blob(audioChunks);
                                    res.innerHTML = '<span class="loader">Analyzing spectrogram amd melody...</span>';
                                    try {
                                        const response = await fetch('/search', { method: 'POST', body: audioBlob });
                                        const data = await response.json();
                                        if(data.score >= 10) {
                                            res.innerText = data.song + ' (Score: ' + data.score + ')';
                                        } else {
                                            res.innerText = 'Melody haven`t been recognized';
                                        }
                                    } catch (e) {
                                        res.innerText = 'Error connecting to server';
                                    }
                                };
            
                                mediaRecorder.start();
                                btn.classList.add('recording');
                                btn.innerText = 'Listening (30s)...';
                                res.innerText = 'Recording...';
                                
                                setTimeout(() => {
                                    mediaRecorder.stop();
                                    stream.getTracks().forEach(t => t.stop());
                                    btn.classList.remove('recording');
                                    btn.innerText = 'Start Listening';
                                }, 30000);
                                
                            } catch (e) {
                                alert('Microphone access denied!');
                            }
                        };
                    </script>
                </body>
                </html>
                """;
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, html.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(html.getBytes()); }
        });
        server.createContext("/search", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                InputStream is = exchange.getRequestBody();
                File tempFile = new File("temp_query.webm");
                Files.copy(is, tempFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                SearchResult result = engine.searchFromFile(tempFile);
                String json = "{\"song\": \"" + result.songName.replace("\"", "\\\"") + "\", \"score\": " + result.score + "}";
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(200, json.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(json.getBytes()); }
            }
        });

        server.setExecutor(null);
        server.start();
        System.out.println("Web Server started successfully");
    }
}