// Copyright 2019 Google LLC
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.cloud.broker.apps.brokerserver.accesstokens.providers;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.List;

import com.google.api.client.auth.oauth2.*;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.googleapis.util.Utils;
import com.google.api.services.iam.v1.Iam;
import com.google.api.services.iam.v1.model.SignJwtRequest;
import com.google.api.services.iam.v1.model.SignJwtResponse;
import io.grpc.Status;

import com.google.cloud.broker.oauth.GoogleCredentialsDetails;
import com.google.cloud.broker.oauth.GoogleCredentialsFactory;
import com.google.cloud.broker.apps.brokerserver.accesstokens.AccessToken;
import com.google.cloud.broker.utils.TimeUtils;
import com.google.cloud.broker.utils.Constants;


public abstract class AbstractSignedJWTProvider extends AbstractProvider {

    private boolean brokerIssuer;
    private final static String IAM_API = "https://www.googleapis.com/auth/iam";

    AbstractSignedJWTProvider(boolean brokerIssuer) {
        this.brokerIssuer = brokerIssuer;
    }

    private boolean isBrokerIssuer() {
        return brokerIssuer;
    }

    private Iam createIamService(GoogleCredentialsDetails details) {
        Credential credential = new Credential(BearerToken.authorizationHeaderAccessMethod()).setAccessToken(details.getAccessToken());
        return new Iam.Builder(Utils.getDefaultTransport(), Utils.getDefaultJsonFactory(), credential)
            .setApplicationName(Constants.APPLICATION_NAME).build();
    }

    private String getSignedJWT(String owner, List<String> scopes) {
        // Get broker's service account details
        GoogleCredentialsDetails details = GoogleCredentialsFactory.createCredentialsDetails(
            List.of(IAM_API),true);

        // Create the JWT payload
        long jwtLifetime = 30;
        long iat = TimeUtils.currentTimeMillis() / 1000L;
        long exp = iat + jwtLifetime;
        HashMap<String, Object> jwtPayload = new HashMap<>();
        jwtPayload.put("scope", String.join(",", scopes));
        jwtPayload.put("aud", "https://www.googleapis.com/oauth2/v4/token");
        jwtPayload.put("iat", iat);
        jwtPayload.put("exp", exp);
        String serviceAccount;
        String googleIdentity = getGoogleIdentity(owner);
        if (isBrokerIssuer()) {
            jwtPayload.put("sub", googleIdentity);
            jwtPayload.put("iss", details.getEmail());
            serviceAccount = details.getEmail();
        } else {
            jwtPayload.put("iss", googleIdentity);
            serviceAccount = googleIdentity;
        }

        // Get a signed JWT
        SignJwtResponse response;
        try {
            // Create the SignJWT request body
            SignJwtRequest requestBody = new SignJwtRequest();
            requestBody.setPayload(new JacksonFactory().toString(jwtPayload));

            // Create the SignJWT request
            Iam iamService = createIamService(details);
            String name = String.format("projects/-/serviceAccounts/%s", serviceAccount);
            Iam.Projects.ServiceAccounts.SignJwt request =
                iamService.projects().serviceAccounts().signJwt(name, requestBody);

            // Execute the request
            response = request.execute();
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == 403) {
                throw Status.PERMISSION_DENIED.asRuntimeException();
            }
            else {
                throw new RuntimeException(e);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return response.getSignedJwt();
    }

    private AccessToken tradeSignedJWTForAccessToken(String signedJWT) {
        HttpTransport httpTransport;
        JsonFactory jsonFactory;
        TokenResponse response;
        try {
            httpTransport = GoogleNetHttpTransport.newTrustedTransport();
            jsonFactory = JacksonFactory.getDefaultInstance();
            TokenRequest request = new TokenRequest(
                httpTransport,
                jsonFactory,
                new GenericUrl("https://www.googleapis.com/oauth2/v4/token"),
                "assertion");
            request.put("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
            request.put("assertion", signedJWT);
            response = request.execute();
        } catch (TokenResponseException e) {
            throw Status.PERMISSION_DENIED.asRuntimeException();
        } catch (IOException | GeneralSecurityException e) {
            throw new RuntimeException(e);
        }
        return new AccessToken(
            response.getAccessToken(),
            TimeUtils.currentTimeMillis() + response.getExpiresInSeconds() * 1000);
    }

    @Override
    public AccessToken getAccessToken(String owner, List<String> scopes) {
        // Get signed JWT
        String signedJWT = getSignedJWT(owner, scopes);

        // Obtain and return new access token for the owner
        return tradeSignedJWTForAccessToken(signedJWT);
    }

    public abstract String getGoogleIdentity(String owner);
}
