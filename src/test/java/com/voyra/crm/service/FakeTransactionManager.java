package com.voyra.crm.service;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;

/**
 * Spring's real transaction lifecycle without a database: commit, rollback, synchronizations (afterCommit), REQUIRES_NEW
 * suspend/resume, and a participant marking the whole transaction rollback-only (the commit then fails with
 * UnexpectedRollbackException, as with a real Spring Data repository that threw inside the caller's transaction).
 */
class FakeTransactionManager extends AbstractPlatformTransactionManager {

    static final class Tx implements SmartTransactionObject {
        boolean active;
        boolean rollbackOnly;

        @Override
        public boolean isRollbackOnly() {
            return rollbackOnly;
        }

        @Override
        public void flush() {
        }
    }

    private final ThreadLocal<Tx> current = ThreadLocal.withInitial(Tx::new);

    @Override
    protected Object doGetTransaction() {
        return current.get();
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
        return ((Tx) transaction).active;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        Tx tx = (Tx) transaction;
        tx.active = true;
        tx.rollbackOnly = false;
    }

    @Override
    protected Object doSuspend(Object transaction) {
        Tx tx = (Tx) transaction;
        Tx saved = new Tx();
        saved.active = tx.active;
        saved.rollbackOnly = tx.rollbackOnly;
        tx.active = false;
        tx.rollbackOnly = false;
        return saved;
    }

    @Override
    protected void doResume(Object transaction, Object suspendedResources) {
        Tx tx = (Tx) transaction;
        Tx saved = (Tx) suspendedResources;
        tx.active = saved.active;
        tx.rollbackOnly = saved.rollbackOnly;
    }

    @Override
    protected void doSetRollbackOnly(DefaultTransactionStatus status) {
        ((Tx) status.getTransaction()).rollbackOnly = true;
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        Tx tx = (Tx) transaction;
        tx.active = false;
        tx.rollbackOnly = false;
    }
}
