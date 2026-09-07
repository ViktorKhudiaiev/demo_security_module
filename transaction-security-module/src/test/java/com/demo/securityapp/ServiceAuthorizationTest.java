package com.demo.securityapp;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class ServiceAuthorizationTest {
    String app="app-credential-with-at-least-32-characters";
    String admin="admin-credential-with-at-least-32-characters";
    ServiceAuthorization filter=new ServiceAuthorization(new MockEnvironment().withProperty("processor.app-token",app).withProperty("processor.admin-token",admin));
    @Test void unauthenticatedRequestsCannotReachInternalOperations()throws Exception{
        MockHttpServletResponse response=new MockHttpServletResponse();MockFilterChain chain=new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET","/internal/operations/test"),response,chain);
        assertThat(response.getStatus()).isEqualTo(401);assertThat(chain.getRequest()).isNull();
    }
    @Test void applicationCredentialCannotHoldAccountsOrConfigureFaults()throws Exception{
        for(String path:new String[]{"/internal/accounts/test/hold","/internal/test/faults"}){
            MockHttpServletRequest request=new MockHttpServletRequest("POST",path);request.addHeader("Authorization","Bearer "+app);
            MockHttpServletResponse response=new MockHttpServletResponse();filter.doFilter(request,response,new MockFilterChain());assertThat(response.getStatus()).isEqualTo(401);
        }
    }
    @Test void operatorCredentialCanReachHoldEndpoint()throws Exception{
        MockHttpServletRequest request=new MockHttpServletRequest("POST","/internal/accounts/test/hold");request.addHeader("Authorization","Bearer "+admin);
        MockHttpServletResponse response=new MockHttpServletResponse();MockFilterChain chain=new MockFilterChain();filter.doFilter(request,response,chain);assertThat(chain.getRequest()).isSameAs(request);
    }
    @Test void healthDoesNotRequireServiceCredential()throws Exception{
        MockHttpServletRequest request=new MockHttpServletRequest("GET","/health");MockFilterChain chain=new MockFilterChain();filter.doFilter(request,new MockHttpServletResponse(),chain);assertThat(chain.getRequest()).isSameAs(request);
    }
    @Test void equalCredentialsAreRefusedAtStartup(){assertThatThrownBy(()->new ServiceAuthorization(new MockEnvironment().withProperty("processor.app-token",app).withProperty("processor.admin-token",app))).isInstanceOf(IllegalArgumentException.class);}
}
