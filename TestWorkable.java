import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class TestWorkable {
    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String token = "salla";
        URI api = URI.create("https://apply.workable.com/api/v3/accounts/" + token + "/jobs");
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(api)
                .header("User-Agent", "TalentShiftIntegrationHub/1.0 (IntegrationHub)")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"\",\"location\":[],\"department\":[],\"worktype\":[],\"remote\":[]}"))
                .build();
                
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("Status: " + response.statusCode());
        System.out.println("Body: " + response.body().substring(0, Math.min(response.body().length(), 500)));
    }
}
