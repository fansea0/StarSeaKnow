package com.fansea.ai.openapi.retrieval;

import com.fansea.ai.auth.AuthContext;
import com.fansea.ai.openapi.credential.ApiCredentialResolver;
import com.fansea.ai.openapi.credential.CredentialType;
import com.fansea.ai.openapi.credential.RagKnowledgeScopeSnapshot;
import com.fansea.ai.service.RagService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class RetrievalDeadlineExecutorTest {

    @Test
    void cancelsHangingDependencyAtDeadlineAndClearsWorkerAuthContext() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicReference<Long> observedTenant = new AtomicReference<>();
        AtomicBoolean contextClearedAfterInterrupt = new AtomicBoolean();
        RagService hanging = mock(RagService.class);
        doAnswer(invocation -> {
            observedTenant.set(AuthContext.current().getTenantId());
            try {
                new CountDownLatch(1).await();
                return List.of();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return List.of();
            } finally {
                interrupted.countDown();
            }
        }).when(hanging).retrieve(any());
        RetrievalDeadlineExecutor executor = new RetrievalDeadlineExecutor(hanging, Duration.ofMillis(40), 1, 1);
        AuthContext context = externalContext();

        assertThatThrownBy(() -> executor.retrieve(new RetrievalQuery("q", Set.of(11L), 1, 0), context))
                .isInstanceOf(TimeoutException.class);
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        executor.executeProbe(() -> contextClearedAfterInterrupt.set(AuthContext.current() == null));
        assertThat(contextClearedAfterInterrupt).isTrue();
        assertThat(observedTenant).hasValue(22L);
        executor.close();
    }

    private AuthContext externalContext() {
        ApiCredentialResolver.ResolvedCredential credential = new ApiCredentialResolver.ResolvedCredential(
                41L, 22L, CredentialType.RAG_RETRIEVAL, "test", "active", null, List.of(),
                60, 10, 5, 1L, new RagKnowledgeScopeSnapshot(Set.of(11L)));
        return AuthContext.external(credential);
    }
}
