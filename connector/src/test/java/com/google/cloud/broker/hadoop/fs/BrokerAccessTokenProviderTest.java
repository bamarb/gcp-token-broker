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

package com.google.cloud.broker.hadoop.fs;

import javax.security.auth.Subject;
import java.io.IOException;
import java.security.PrivilegedAction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import org.apache.hadoop.security.UserGroupInformation;
import org.junit.*;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import io.grpc.*;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystem;

import static com.google.cloud.broker.hadoop.fs.SpnegoUtilsTest.TGT_ERROR;
import static com.google.cloud.broker.hadoop.fs.TestingTools.*;
import com.google.cloud.hadoop.util.AccessTokenProvider.AccessToken;
import com.google.cloud.broker.testing.FakeKDC;
import com.google.cloud.broker.protobuf.*;

@RunWith(PowerMockRunner.class)
@PowerMockIgnore({"com.sun.org.apache.xerces.*", "javax.xml.*", "javax.activation.*", "org.xml.*", "org.w3c.*", "javax.crypto.*", "javax.net.ssl.*", "javax.security.*", "org.ietf.jgss.*"})
@PrepareForTest({GrpcUtils.class})  // Classes to be mocked
public class BrokerAccessTokenProviderTest {

    private static FakeKDC fakeKDC;

    @Rule
    public static final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

    @BeforeClass
    public static void setUpClass() {
        TestingTools.initHadoop();
        fakeKDC = new FakeKDC(REALM);
        fakeKDC.start();
        fakeKDC.createPrincipal(ALICE);
        fakeKDC.createPrincipal(BROKER);
    }

    @AfterClass
    public static void tearDownClass() {
        fakeKDC.stop();
    }

    public AccessToken refresh(Configuration conf) {
        BrokerDelegationTokenBinding binding = new BrokerDelegationTokenBinding();
        Text service = new Text(MOCK_BUCKET);
        binding.bindToFileSystem(new GoogleHadoopFileSystem(), service);
        BrokerAccessTokenProvider provider;
        try {
            provider = (BrokerAccessTokenProvider) binding.deployUnbonded();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        provider.setConf(conf);
        provider.refresh();
        return provider.getAccessToken();
    }

    private static class FakeServer extends TestingTools.FakeBrokerImpl {

        public FakeServer(FakeKDC fakeKDC) {
            super(fakeKDC);
        }

        @Override
        public void getAccessToken(GetAccessTokenRequest request, StreamObserver<GetAccessTokenResponse> responseObserver) {
            try {
                String authenticatedUser = authenticateUser();
                GetAccessTokenResponse response = GetAccessTokenResponse.newBuilder()
                    .setAccessToken("FakeAccessToken/AuthenticatedUser=" + authenticatedUser + ";Owner=" + request.getOwner() + ";Target=" + request.getTarget())
                    .setExpiresAt(11111111111111L)
                    .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            }
            catch (StatusRuntimeException e) {throw new RuntimeException(e);}
        }
    }

    @Test
    public void testProviderRefreshWhileNotLoggedIn() {
        try {
            Configuration conf = TestingTools.getBrokerConfig();
            refresh(conf);
            fail();
        } catch (Exception e) {
            assertEquals(RuntimeException.class, e.getClass());
            assertEquals(
                "User is not logged-in with Kerberos or cannot authenticate with the broker. Kerberos error message: " + TGT_ERROR,
                e.getMessage()
            );
        }
    }

    @Test
    public void testProviderRefresh() throws IOException {
        TestingTools.startServer(new FakeServer(fakeKDC), grpcCleanup);
        Configuration conf = TestingTools.getBrokerConfig();
        Subject alice = fakeKDC.login(ALICE);
        UserGroupInformation.loginUserFromSubject(alice);
        AccessToken token = refresh(conf);
        assertEquals("FakeAccessToken/AuthenticatedUser=" + ALICE + ";Owner=" + ALICE + ";Target=" + MOCK_BUCKET, token.getToken());
        UserGroupInformation.setLoginUser(null);
    }

}
