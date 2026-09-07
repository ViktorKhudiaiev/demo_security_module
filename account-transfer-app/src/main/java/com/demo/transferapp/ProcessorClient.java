package com.demo.transferapp;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Trusted service client, not an end-user identity provider. */
public class ProcessorClient {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json=new ObjectMapper();
    private final String baseUrl,token;
    public ProcessorClient(String baseUrl,String token) {
        if(token==null||token.isBlank())throw new IllegalArgumentException("Processor credential required");
        this.baseUrl=baseUrl.replaceAll("/+$", "");this.token=token;
    }
    public Map<String,Object> account(UUID id) {return request("GET","/internal/accounts/"+id,null);}
    public Map<String,Object> register(UUID id,String name) {return request("POST","/internal/accounts",Map.of("id",id,"name",name));}
    public Map<String,Object> operation(UUID id) {return request("GET","/internal/operations/"+id,null);}
    @SuppressWarnings("unchecked")
    private Map<String,Object> request(String method,String path,Object body) {
        try {
            var request=HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(10))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()<200||response.statusCode()>=300)throw new RemoteException(response.statusCode());
            return json.readValue(response.body(),Map.class);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Processor request interrupted",e);}
        catch(IOException e){throw new IllegalStateException("Processor unavailable",e);}
    }
    public static class RemoteException extends IllegalStateException {
        private final int status;
        public RemoteException(int status){super("Processor returned HTTP "+status);this.status=status;}
        public int status(){return status;}
    }
}
