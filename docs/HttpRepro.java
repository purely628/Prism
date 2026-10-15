import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class HttpRepro {
    public static void main(String[] args) throws Exception {
        String body = "{\"query\":\"令牌桶\",\"topK\":3}";
        String url = "http://127.0.0.1:8200/internal/kb/3/retrieve";

        // 变体1：与 AgentClient 完全一致（默认版本 + timeout + header）
        HttpClient c1 = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest r1 = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        System.out.println("V1 default: " + c1.send(r1, HttpResponse.BodyHandlers.ofString()).statusCode());

        // 变体2：显式 HTTP_1_1
        HttpClient c2 = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        System.out.println("V2 http1.1: " + c2.send(r1, HttpResponse.BodyHandlers.ofString()).statusCode());

        // 变体3：不带 request timeout
        HttpRequest r3 = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        System.out.println("V3 no-rt: " + c2.send(r3, HttpResponse.BodyHandlers.ofString()).statusCode());

        // 变体4：Content-Type 带 charset
        HttpRequest r4 = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        System.out.println("V4 charset: " + c2.send(r4, HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
