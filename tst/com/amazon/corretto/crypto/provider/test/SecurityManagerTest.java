// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package com.amazon.corretto.crypto.provider.test;

import static org.junit.Assert.assertEquals;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.AccessController;
import java.security.MessageDigest;
import java.security.Permission;
import java.security.Security;
import java.util.concurrent.atomic.AtomicReference;

import com.amazon.corretto.crypto.provider.AmazonCorrettoCryptoProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SecurityManagerTest {
    private AtomicReference<Thread> threadToDeny = new AtomicReference<>(null);

    @Before
    public void priv_setUp() throws Exception {
        // JCE requires permissions to do some initialization work (e.g. reading jurisdictional permissions). Let this
        // init happen by doing some dummy cipher work with the built-in JCE providers first.
        Security.removeProvider("AmazonCorrettoCryptoProvider");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(new byte[16], "AES"));
        c.doFinal();

        Security.insertProviderAt(AmazonCorrettoCryptoProvider.INSTANCE, 1);
        System.setSecurityManager(new OneThreadSecurityManager(threadToDeny));
    }

    @After
    public void priv_tearDown() throws Exception {
        threadToDeny.set(null);

        System.setSecurityManager(null);

        Security.removeProvider("AmazonCorrettoCryptoProvider");
    }

    @Test(expected = SecurityException.class)
    public void sanityCheck_securityManager_doesDeny() throws Exception {
        try {
            threadToDeny.set(Thread.currentThread());

            Object.class.getDeclaredMethod("clone").setAccessible(true);
        } finally {
            threadToDeny.set(null);
        }
    }

    @Test
    public void testDigestsUnderSecurityManager() throws Exception {
        try {
            threadToDeny.set(Thread.currentThread());

            MessageDigest.getInstance("SHA-1").digest(new byte[10]);

            MessageDigest md = MessageDigest.getInstance("SHA-1");
            assertEquals("AmazonCorrettoCryptoProvider", md.getProvider().getName());

            md.update(new byte[10]);
            md.digest(new byte[10]);
        } finally {
            threadToDeny.set(null);
        }
    }

    @Test
    public void testAESUnderSecurityManager() throws Exception {
        try {
            threadToDeny.set(Thread.currentThread());

            Cipher c = Cipher.getInstance("AES/GCM/NoPadding", "AmazonCorrettoCryptoProvider");
            assertEquals("AmazonCorrettoCryptoProvider", c.getProvider().getName());

            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(new byte[16], "AES"));
            c.update(new byte[10]);
            c.doFinal();
        } finally {
            threadToDeny.set(null);
        }
    }

    private static class OneThreadSecurityManager extends SecurityManager {
        private final AtomicReference<Thread> threadToDeny;

        private OneThreadSecurityManager(AtomicReference<Thread> threadToDeny) {
            this.threadToDeny = threadToDeny;
        }

        @Override
        public void checkPermission(Permission perm) {
            if (Thread.currentThread() != threadToDeny.get()) return;

            Thread oldThread = null;
            try {
                AccessController.checkPermission(perm);
            } catch (SecurityException e) {

                oldThread = threadToDeny.getAndSet(null);

                e.printStackTrace();

                throw e;
            } finally {
                threadToDeny.set(oldThread);
            }
        }
    }
}
