package com.starsea.ai.agent.debug;

import com.starsea.ai.agent.AgentDeletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;

class DebugContextLifecycleTest {
    @Configuration @EnableTransactionManagement static class Transactions { }

    @Test void agent_delete_clears_all_its_users_only_after_commit_and_not_after_rollback() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Transactions.class);
            context.registerBean(DebugContextProperties.class);
            context.registerBean(DebugContextStore.class);
            context.refresh();
            var store = context.getBean(DebugContextStore.class);
            var owner = new DebugContextStore.Owner(9, 71);
            var otherOwner = new DebugContextStore.Owner(9, 72);
            var a = store.open(owner, 101, null); var b = store.open(otherOwner, 101, null);
            var unrelated = store.open(owner, 102, null);
            a.close(); b.close(); unrelated.close();
            var tx = new TransactionTemplate(new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
                @Override protected void doCommit(DefaultTransactionStatus status) { }
                @Override protected void doRollback(DefaultTransactionStatus status) { }
            });
            tx.executeWithoutResult(status -> {
                context.publishEvent(new AgentDeletedEvent(9, 101));
                store.open(owner, 101, a.id()).close();
                status.setRollbackOnly();
            });
            store.open(owner, 101, a.id()).close();
            tx.executeWithoutResult(status -> {
                context.publishEvent(new AgentDeletedEvent(9, 101));
                store.open(owner, 101, a.id()).close();
            });
            assertThatThrownBy(() -> store.open(owner, 101, a.id())).extracting("status").isEqualTo(410);
            assertThatThrownBy(() -> store.open(otherOwner, 101, b.id())).extracting("status").isEqualTo(410);
            store.open(owner, 102, unrelated.id()).close();
        }
    }
}
