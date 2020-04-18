/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2016 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 *
 * This file incorporates work covered by the following copyright and
 * permission notice:
 *
 * Copyright 2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Portions Copyright [2016-2019] [Payara Foundation and/or its affiliates]

package org.apache.catalina.session;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.LogFacade;
import org.apache.catalina.Session;
import org.apache.catalina.Store;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.security.SecurityUtil;
import org.apache.catalina.util.LifecycleSupport;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Extends the <b>ManagerBase</b> class to implement most of the
 * functionality required by a Manager which supports any kind of
 * persistence, even if only for restarts.
 * <p>
 * <b>IMPLEMENTATION NOTE</b>:  Correct behavior of session storing and
 * reloading depends upon external calls to the <code>start()</code> and
 * <code>stop()</code> methods of this class at the correct times.
 *
 * @author Craig R. McClanahan
 * @author Jean-Francois Arcand
 * @version $Revision: 1.16 $ $Date: 2007/05/05 05:32:19 $
 */
public abstract class PersistentManagerBase extends ManagerBase implements Lifecycle, PropertyChangeListener {

    // ---------------------------------------------------- Security Classes
    private class PrivilegedStoreClear
        implements PrivilegedExceptionAction<Void> {

        PrivilegedStoreClear() {
            // NOOP
        }

        @Override
        public Void run() throws Exception {
            PersistentManagerBase.this.store.clear();
            return null;
        }
    }

    private class PrivilegedStoreRemove
        implements PrivilegedExceptionAction<Void> {

        private final String id;

        PrivilegedStoreRemove(final String id) {
            this.id = id;
        }

        @Override
        public Void run() throws Exception {
            PersistentManagerBase.this.store.remove(this.id);
            return null;
        }
    }

    private class PrivilegedStoreLoad
        implements PrivilegedExceptionAction<Session> {

        private final String id;

        PrivilegedStoreLoad(final String id) {
            this.id = id;
        }

        @Override
        public Session run() throws Exception {
            return PersistentManagerBase.this.store.load(this.id);
        }
    }

    private class PrivilegedStoreSave
        implements PrivilegedExceptionAction<Void> {

        private final Session session;

        PrivilegedStoreSave(final Session session) {
            this.session = session;
        }

        @Override
        public Void run() throws Exception {
            PersistentManagerBase.this.store.save(this.session);
            return null;
        }
    }

    private class PrivilegedStoreKeys
        implements PrivilegedExceptionAction<String[]> {

        PrivilegedStoreKeys() {
            // NOOP
        }

        @Override
        public String[] run() throws Exception {
            return PersistentManagerBase.this.store.keys();
        }
    }
    // ----------------------------------------------------- Instance Variables


    /**
     * The descriptive information about this implementation.
     */
    private static final String info = "PersistentManagerBase/1.0";

    /**
     * The descriptive name of this Manager implementation (for logging).
     */
    protected static final String name = "PersistentManagerBase";

    /**
     * The lifecycle event support for this component.
     */
    protected LifecycleSupport lifecycle = new LifecycleSupport(this);


    /**
     * The maximum number of active Sessions allowed, or -1 for no limit.
     */
    private int maxActiveSessions = -1;


    /**
     * Has this component been started yet?
     */
    private boolean started = false;


    /**
     * Store object which will manage the Session store.
     */
    private Store store = null;


    /**
     * Whether to save and reload sessions when the Manager <code>unload</code>
     * and <code>load</code> methods are called.
     */
    private boolean saveOnRestart = true;


    /**
     * How long a session must be idle before it should be backed up.
     * -1 means sessions won't be backed up.
     */
    private int maxIdleBackup = -1;


    /**
     * Minimum time a session must be idle before it is swapped to disk.
     * This overrides maxActiveSessions, to prevent thrashing if there are lots
     * of active sessions. Setting to -1 means it's ignored.
     */
    private int minIdleSwap = -1;

    /**
     * The maximum time a session may be idle before it should be swapped
     * to file just on general principle. Setting this to -1 means sessions
     * should not be forced out.
     */
    private int maxIdleSwap = -1;


    // START SJSAS 6406580
    /**
     * The set of invalidated Sessions for this Manager, keyed by
     * session identifier.
     */
    protected ConcurrentHashMap<String, Long> invalidatedSessions
        = new ConcurrentHashMap<>();

    // Specifies for how long we're going to remember invalidated session ids
    private long rememberInvalidatedSessionIdMilliSecs = 60000L;
    // END SJSAS 6406580


    // ------------------------------------------------------------- Properties

    /**
     * Perform the background processes for this Manager
     */
    public void backgroundProcess() {
        processExpires();
        processPersistenceChecks();
        // START SJSAS 6406580
        processInvalidatedSessions();
        // END SJSAS 6406580
        if (getStore() instanceof StoreBase) {
            ((StoreBase) this.getStore()).processExpires();
        }
    }

    /**
     * Sets the option to back sessions up to the Store after they
     * are used in a request. Sessions remain available in memory
     * after being backed up, so they are not passivated as they are
     * when swapped out. The value set indicates how old a session
     * may get (since its last use) before it must be backed up: -1
     * means sessions are not backed up.
     * <p>
     * Note that this is not a hard limit: sessions are checked
     * against this age limit periodically according to <b>checkInterval</b>.
     * This value should be considered to indicate when a session is
     * ripe for backing up.
     * <p>
     * So it is possible that a session may be idle for maxIdleBackup +
     * checkInterval seconds, plus the time it takes to handle other
     * session expiration, swapping, etc. tasks.
     *
     * @param backup The number of seconds after their last accessed
     *               time when they should be written to the Store.
     */
    public void setMaxIdleBackup(final int backup) {

        if (backup == this.maxIdleBackup) {
            return;
        }
        final int oldBackup = this.maxIdleBackup;
        this.maxIdleBackup = backup;
        this.support.firePropertyChange("maxIdleBackup",
            Integer.valueOf(oldBackup),
            Integer.valueOf(this.maxIdleBackup));

    }


    /**
     * Sets the time in seconds after which a session should be swapped out of
     * memory to disk.
     */
    public void setMaxIdleSwap(final int max) {

        if (max == this.maxIdleSwap) {
            return;
        }
        final int oldMaxIdleSwap = this.maxIdleSwap;
        this.maxIdleSwap = max;
        this.support.firePropertyChange("maxIdleSwap",
            Integer.valueOf(oldMaxIdleSwap),
            Integer.valueOf(this.maxIdleSwap));

    }


    /**
     * Sets the minimum time in seconds that a session must be idle before
     * it can be swapped out of memory due to maxActiveSession. Set it to -1
     * if it can be swapped out at any time.
     */
    public void setMinIdleSwap(final int min) {

        if (this.minIdleSwap == min) {
            return;
        }
        final int oldMinIdleSwap = this.minIdleSwap;
        this.minIdleSwap = min;
        this.support.firePropertyChange("minIdleSwap",
            Integer.valueOf(oldMinIdleSwap),
            Integer.valueOf(this.minIdleSwap));

    }


    /**
     * Set the Container with which this Manager has been associated.  If
     * it is a Context (the usual case), listen for changes to the session
     * timeout property.
     *
     * @param container The associated Container
     */
    @Override
    public void setContainer(final Container container) {

        // De-register from the old Container (if any)
        if (this.container instanceof Context) {
            this.container.removePropertyChangeListener(this);
        }

        // Default processing provided by our superclass
        super.setContainer(container);

        // Register with the new Container (if any)
        if (this.container instanceof Context) {
            setMaxInactiveIntervalSeconds
                (((Context) this.container).getSessionTimeout() * 60);
            this.container.addPropertyChangeListener(this);
        }

        // START SJSAS 6406580
        if (container instanceof StandardContext) {
            // Determine for how long we're going to remember invalidated
            // session ids
            final StandardContext ctx = (StandardContext) container;
            final int frequency = ctx.getManagerChecksFrequency();
            final int reapIntervalSeconds = ctx.getBackgroundProcessorDelay();
            this.rememberInvalidatedSessionIdMilliSecs
                = frequency * reapIntervalSeconds * 1000L * 2;
            if (this.rememberInvalidatedSessionIdMilliSecs <= 0) {
                this.rememberInvalidatedSessionIdMilliSecs = 60000L;
            }
        }
        // END SJSAS 6406580
    }


    /**
     * Return descriptive information about this Manager implementation and
     * the corresponding version number, in the format
     * <code>&lt;description&gt;/&lt;version&gt;</code>.
     */
    @Override
    public String getInfo() {
        return (info);
    }


    /**
     * Return true, if the session id is loaded in memory
     * otherwise false is returned
     *
     * @param id The session id for the session to be searched for
     */
    public boolean isLoaded(final String id) {
        try {
            return super.findSession(id) != null;
        } catch (final IOException e) {
            final String msg = MessageFormat.format(rb.getString(LogFacade.CHECKING_IS_LOADED_EXCEPTION),
                id, e.getMessage());
            log.log(Level.SEVERE, msg, e);
        }

        return false;
    }


    /**
     * Return the maximum number of active Sessions allowed, or -1 for
     * no limit.
     */
    public int getMaxActiveSessions() {
        return (this.maxActiveSessions);
    }


    /**
     * Set the maximum number of active Sessions allowed, or -1 for
     * no limit.
     *
     * @param max The new maximum number of sessions
     */
    public void setMaxActiveSessions(final int max) {

        final int oldMaxActiveSessions = this.maxActiveSessions;
        this.maxActiveSessions = max;
        this.support.firePropertyChange("maxActiveSessions",
            Integer.valueOf(oldMaxActiveSessions),
            Integer.valueOf(this.maxActiveSessions));
    }


    /**
     * Return the descriptive short name of this Manager implementation.
     */
    @Override
    public String getName() {
        return (name);
    }


    /**
     * Get the started status.
     */
    protected boolean isStarted() {
        return this.started;
    }


    /**
     * Set the started flag to false
     */
    private void setStopped() {
        this.started = false;
    }


    /**
     * Set the Store object which will manage persistent Session
     * storage for this Manager.
     *
     * @param store the associated Store
     */
    public void setStore(final Store store) {
        this.store = store;
        store.setManager(this);
    }


    /**
     * Return the Store object which manages persistent Session
     * storage for this Manager.
     */
    public Store getStore() {

        return (this.store);

    }


    /**
     * Indicates whether sessions are saved when the Manager is shut down
     * properly. This requires the unload() method to be called.
     */
    public boolean getSaveOnRestart() {

        return this.saveOnRestart;

    }


    /**
     * Set the option to save sessions to the Store when the Manager is
     * shut down, then loaded when the Manager starts again. If set to
     * false, any sessions found in the Store may still be picked up when
     * the Manager is started again.
     *
     * @param saveOnRestart true if sessions should be saved on restart, false if
     *                      they should be ignored.
     */
    public void setSaveOnRestart(final boolean saveOnRestart) {

        if (saveOnRestart == this.saveOnRestart) {
            return;
        }

        final boolean oldSaveOnRestart = this.saveOnRestart;
        this.saveOnRestart = saveOnRestart;
        this.support.firePropertyChange("saveOnRestart",
            Boolean.valueOf(oldSaveOnRestart),
            Boolean.valueOf(this.saveOnRestart));

    }


    // --------------------------------------------------------- Public Methods


    /**
     * Releases any resources held by this session manager.
     */
    @Override
    public void release() {
        super.release();
        clearStore();
    }


    /**
     * Clear all sessions from the Store.
     */
    public void clearStore() {

        if (this.store == null) {
            return;
        }

        try {
            if (SecurityUtil.isPackageProtectionEnabled()) {
                try {
                    AccessController.doPrivileged(new PrivilegedStoreClear());
                } catch (final PrivilegedActionException ex) {
                    final Exception exception = ex.getException();
                    log.log(Level.SEVERE, LogFacade.CLEARING_STORE_EXCEPTION, exception);
                }
            } else {
                this.store.clear();
            }
        } catch (final IOException e) {
            log.log(Level.SEVERE, LogFacade.CLEARING_STORE_EXCEPTION, e);
        }

    }


    /**
     * Invalidate all sessions that have expired.
     * Hercules: modified method
     */
    protected void processExpires() {

        if (!this.started) {
            return;
        }

        final Session[] sessions = findSessions();

        for (final Session session1 : sessions) {
            final StandardSession session = (StandardSession) session1;
            // START CR 6363689
            if (!session.getIsValid() || session.hasExpired()) {
                // END CR 6363689
                if (session.lockBackground()) {
                    try {
                        session.expire();
                    } finally {
                        session.unlockBackground();
                    }
                }
            }
        }
    }


    /**
     * Called by the background thread after active sessions have
     * been checked for expiration, to allow sessions to be
     * swapped out, backed up, etc.
     */
    public void processPersistenceChecks() {
        processMaxIdleSwaps();
        processMaxActiveSwaps();
        processMaxIdleBackups();
    }


    /**
     * Purges those session ids from the map of invalidated session ids whose
     * time has come up
     */
    protected void processInvalidatedSessions() {
        if (!this.started) {
            return;
        }

        final long timeNow = System.currentTimeMillis();
        for (final Map.Entry<String, Long> e : this.invalidatedSessions.entrySet()) {
            final String id = e.getKey();
            final Long timeAdded = e.getValue();
            if ((timeAdded == null) || (timeNow - timeAdded > this.rememberInvalidatedSessionIdMilliSecs)) {
                removeFromInvalidatedSessions(id);
            }
        }
    }


    /**
     * Return a new session object as long as the number of active
     * sessions does not exceed <b>maxActiveSessions</b>. If there
     * aren't too many active sessions, or if there is no limit,
     * a session is created or retrieved from the recycled pool.
     *
     * @return the new session
     * @throws IllegalStateException if a new session cannot be
     *                               instantiated for any reason
     */
    @Override
    public Session createSession() {
        if (((this.maxActiveSessions >= 0) && (this.sessions.size() >= this.maxActiveSessions))) {
            if (this.store == null) {
                //too many active sessions and no store
                throw new IllegalStateException(rb.getString(LogFacade.CREATE_SESSION_EXCEPTION));
            }
            //swap out active sessions to store
            processMaxActiveSwaps();
        }
        return (super.createSession());

    }


    // START S1AS8PE 4817642

    /**
     * Construct and return a new session object, based on the default
     * settings specified by this Manager's properties, using the specified
     * session id.
     * <p>
     * IMPLEMENTATION NOTE: This method must be kept in sync with the
     * createSession method that takes no arguments.
     *
     * @param sessionId the session id to assign to the new session
     * @return the new session, or <code>null</code> if a session with the
     * requested id already exists
     * @throws IllegalStateException if a new session cannot be
     *                               instantiated for any reason
     */
    @Override
    public Session createSession(final String sessionId) {
        if (((this.maxActiveSessions >= 0) && (this.sessions.size() >= this.maxActiveSessions))) {
            if (this.store == null) {
                //too many active sessions and no store
                throw new IllegalStateException(rb.getString(LogFacade.CREATE_SESSION_EXCEPTION));
            }
            //swap out active sessions to store
            processMaxActiveSwaps();
        }
        return (super.createSession(sessionId));
    }
    // END S1AS8PE 4817642


    /**
     * Return the active Session, associated with this Manager, with the
     * specified session id (if any); otherwise return <code>null</code>.
     * This method checks the persistence store if persistence is enabled,
     * otherwise just uses the functionality from ManagerBase.
     *
     * @param id The session id for the session to be returned
     * @throws IllegalStateException if a new session cannot be
     *                               instantiated for any reason
     * @throws IOException           if an input/output error occurs while
     *                               processing this request
     */
    @Override
    public Session findSession(final String id) throws IOException {

        //6406580 START
        if (!this.isSessionIdValid(id)) {
            return null;
        }
        //6406580 END        

        Session session = super.findSession(id);
        if (session != null) {
            return (session);
        }

        // See if the Session is in the Store
        session = swapIn(id);
        return (session);

    }

    /**
     * Return the active Session, associated with this Manager, with the
     * specified session id (if any); otherwise return <code>null</code>.
     * This method first removes the cached copy if removeCachedCopy = true.
     * Then this method checks the persistence store if persistence is enabled,
     * otherwise just uses the functionality from ManagerBase.
     *
     * @param id               The session id for the session to be returned
     * @param removeCachedCopy
     * @throws IllegalStateException if a new session cannot be
     *                               instantiated for any reason
     * @throws IOException           if an input/output error occurs while
     *                               processing this request
     */
    public Session findSession(final String id, final boolean removeCachedCopy) throws IOException {

        Session theSession = super.findSession(id);
        if (theSession != null) {
            if (removeCachedCopy) {
                //remove from manager cache
                removeSuper(theSession);
                //remove from store cache if it exists
                if (getStore() instanceof StoreBase) {
                    ((StoreBase) this.getStore()).removeFromStoreCache(id);
                }
            } else {
                return (theSession);
            }
        }
        //now do full findSession
        theSession = findSession(id);
        return theSession;

    }

    /**
     * used by subclasses of PersistentManagerBase
     * Hercules: added method
     */
    protected Session superFindSession(final String id) throws IOException {
        return super.findSession(id);
    }

    /**
     * Remove this Session from the active Sessions for this Manager,
     * but not from the Store. (Used by the PersistentValve)
     *
     * @param session Session to be removed
     */
    public void removeSuper(final Session session) {
        super.remove(session);
    }

    /**
     * Load all sessions found in the persistence mechanism, assuming
     * they are marked as valid and have not passed their expiration
     * limit. If persistence is not supported, this method returns
     * without doing anything.
     * <p>
     * Note that by default, this method is not called by the MiddleManager
     * class. In order to use it, a subclass must specifically call it,
     * for example in the start() and/or processPersistenceChecks() methods.
     */
    @Override
    public void load() {

        // Initialize our internal data structures
        this.sessions.clear();

        if (this.store == null) {
            return;
        }

        String[] ids = null;
        try {
            if (SecurityUtil.isPackageProtectionEnabled()) {
                try {
                    ids = AccessController.doPrivileged(
                        new PrivilegedStoreKeys());
                } catch (final PrivilegedActionException ex) {
                    final Exception exception = ex.getException();
                    log.log(Level.SEVERE, LogFacade.STORE_LOADING_EXCEPTION, exception);
                }
            } else {
                ids = this.store.keys();
            }
        } catch (final IOException e) {
            log.log(Level.SEVERE, LogFacade.CANNOT_LOAD_SESSION_EXCEPTION, e);
            return;
        }

        final int n = ids.length;
        if (n == 0) {
            return;
        }

        if (log.isLoggable(Level.FINE)) {
            log.log(Level.FINE, LogFacade.LOADING_PERSISTED_SESSIONS, String.valueOf(n));
        }
        for (int i = 0; i < n; i++) {
            try {
                swapIn(ids[i]);
            } catch (final IOException e) {
                log.log(Level.SEVERE, LogFacade.FAILED_LOAD_SESSION_EXCEPTION, e);
            }
        }

    }

    /**
     * Remove this Session from the active Sessions for this Manager,
     * and from the Store.
     *
     * @param session Session to be removed
     */
    @Override
    public void remove(final Session session) {
        remove(session, true);
    }

    /**
     * Remove this Session from the active Sessions for this Manager,
     * and from the Store.
     *
     * @param session          Session to be removed
     * @param persistentRemove - do we remove persistent session too
     */
    public void remove(final Session session, final boolean persistentRemove) {

        super.remove(session);

        if (persistentRemove && this.store != null) {
            removeSession(session.getIdInternal());
        }
    }

    /**
     * Remove this Session from the active Sessions for this Manager,
     * and from the Store.
     *
     * @param id Session's id to be removed
     */
    private void removeSession(final String id) {
        try {
            if (SecurityUtil.isPackageProtectionEnabled()) {
                try {
                    AccessController.doPrivileged(new PrivilegedStoreRemove(id));
                } catch (final PrivilegedActionException ex) {
                    final Exception exception = ex.getException();
                    log.log(Level.SEVERE, LogFacade.STORE_REMOVE_SESSION_EXCEPTION, exception);
                }
            } else {
                this.store.remove(id);
            }
        } catch (final IOException e) {
            log.log(Level.SEVERE, LogFacade.REMOVING_SESSION_EXCEPTION, e);
        }
    }


    // START SJSAS 6406580

    /**
     * Add this Session id to the set of invalidated Session ids for this
     * Manager.
     *
     * @param sessionId session id to be added
     */
    public void addToInvalidatedSessions(final String sessionId) {
        this.invalidatedSessions.put(sessionId, System.currentTimeMillis());
    }


    /**
     * Removes the given session id from the map of invalidated session ids.
     *
     * @param sessionId The session id to remove
     */
    public void removeFromInvalidatedSessions(final String sessionId) {
        this.invalidatedSessions.remove(sessionId);
    }


    /**
     * @return true if the given session id is not contained in the map of
     * invalidated session ids, false otherwise
     */
    public boolean isSessionIdValid(final String sessionId) {
        return (!this.invalidatedSessions.containsKey(sessionId));
    }
    // END SJSAS 6406580    


    /**
     * Save all currently active sessions in the appropriate persistence
     * mechanism, if any.  If persistence is not supported, this method
     * returns without doing anything.
     * <p>
     * Note that by default, this method is not called by the MiddleManager
     * class. In order to use it, a subclass must specifically call it,
     * for example in the stop() and/or processPersistenceChecks() methods.
     */
    @Override
    public void unload() {

        if (this.store == null) {
            return;
        }

        final Session[] sessions = findSessions();
        final int n = sessions.length;
        if (n == 0) {
            return;
        }

        if (log.isLoggable(Level.FINE)) {
            log.log(Level.FINE, LogFacade.SAVING_PERSISTED_SESSION, String.valueOf(n));
        }
        for (int i = 0; i < n; i++) {
            try {
                swapOut(sessions[i]);
            } catch (final IOException e) {
                // This is logged in writeSession()
            }
        }

    }


    // ------------------------------------------------------ Protected Methods

    /**
     * Look for a session in the Store and, if found, restore
     * it in the Manager's list of active sessions if appropriate.
     * The session will be removed from the Store after swapping
     * in, but will not be added to the active session list if it
     * is invalid or past its expiration.
     */
    protected Session swapIn(final String id) throws IOException {
        return swapIn(id, null);
    }

    /**
     * Look for a session in the Store and, if found, restore
     * it in the Manager's list of active sessions if appropriate.
     * The session will be removed from the Store after swapping
     * in, but will not be added to the active session list if it
     * is invalid or past its expiration.
     *
     * @param id      The session id
     * @param version The requested session version
     */
    protected Session swapIn(final String id, final String version) throws IOException {

        ClassLoader webappCl = null;
        ClassLoader curCl = null;

        if (getContainer() != null
            && getContainer().getLoader() != null) {
            webappCl = getContainer().getLoader().getClassLoader();
            curCl = Thread.currentThread().getContextClassLoader();
        }

        Session sess = null;

        if (webappCl != null && curCl != webappCl) {
            try {
                Thread.currentThread().setContextClassLoader(webappCl);
                sess = doSwapIn(id, version);
            } finally {
                Thread.currentThread().setContextClassLoader(curCl);
            }
        } else {
            sess = doSwapIn(id, version);
        }

        return sess;
    }

    /**
     * Look for a session in the Store and, if found, restore
     * it in the Manager's list of active sessions if appropriate.
     * The session will be removed from the Store after swapping
     * in, but will not be added to the active session list if it
     * is invalid or past its expiration.
     */
    private Session doSwapIn(final String id, final String version) throws IOException {

        if (this.store == null) {
            return null;
        }

        Session session = null;
        try {
            if (SecurityUtil.isPackageProtectionEnabled()) {
                try {
                    session = AccessController.doPrivileged(
                        new PrivilegedStoreLoad(id));
                } catch (final PrivilegedActionException ex) {
                    final Exception exception = ex.getException();
                    log.log(Level.SEVERE, LogFacade.STORE_SWAP_IN_EXCEPTION, exception);
                    if (exception instanceof IOException) {
                        throw (IOException) exception;
                    } else if (exception instanceof ClassNotFoundException) {
                        throw (ClassNotFoundException) exception;
                    }
                }
            } else {
                if (version != null) {
                    session = ((StoreBase) this.store).load(id, version);
                } else {
                    session = this.store.load(id);
                }
            }
        } catch (final ClassNotFoundException | ClassCastException e) {
            final String msg = MessageFormat.format(rb.getString(LogFacade.DESERILIZING_SESSION_EXCEPTION),
                id, e);
            log.log(Level.WARNING, msg);
            return null;
        }

        if (session == null) {
            return (null);
        }

        if (!session.isValid()) {
            log.log(this.store.getManager().getDistributable() ? Level.FINE : Level.SEVERE, LogFacade.INVALID_EXPIRED_SESSION_EXCEPTION);
            //6406580 START
            /* - these lines are calling remove on store redundantly
            session.expire();
            removeSession(id);
             */
            //6406580 END            
            return (null);
        }

        if (log.isLoggable(Level.FINE)) {
            log.log(Level.FINE, LogFacade.SWAPPING_SESSION_FROM_STORE, id);
        }
        session.setManager(this);
        // make sure the listeners know about it.
        ((StandardSession) session).tellNew();
        add(session);
        ((StandardSession) session).activate();

        return (session);

    }


    /**
     * Remove the session from the Manager's list of active
     * sessions and write it out to the Store. If the session
     * is past its expiration or invalid, this method does
     * nothing.
     *
     * @param session The Session to write out.
     */
    protected void swapOut(final Session session) throws IOException {

        if (this.store == null || !session.isValid()) {
            return;
        }

        ((StandardSession) session).passivate();
        writeSession(session);
        super.remove(session);
        session.recycle();

    }


    /**
     * Write the provided session to the Store without modifying
     * the copy in memory or triggering passivation events. Does
     * nothing if the session is invalid or past its expiration.
     */
    protected void writeSession(final Session session) throws IOException {
        if (this.store == null || !session.isValid()) {
            return;
        }

        ((StandardContext) getContainer()).sessionPersistedStartEvent(
            (StandardSession) session);

        // If the given session is being persisted after a lock has been
        // acquired out-of-band, its version needs to be incremented
        // here (otherwise, it will have already been incremented at the
        // time the session was acquired via HttpServletRequest.getSession())
        if (isSessionVersioningSupported()
            && ((StandardSession) session).hasNonHttpLockOccurred()) {
            ((StandardSession) session).incrementVersion();
        }

        try {
            if (SecurityUtil.isPackageProtectionEnabled()) {
                try {
                    AccessController.doPrivileged(new PrivilegedStoreSave(session));
                } catch (final PrivilegedActionException ex) {
                    final Exception exception = ex.getException();
                    log.log(Level.SEVERE, LogFacade.STORE_WRITE_SESSION_EXCEPTION,
                        exception);
                }
            } else {
                this.store.save(session);
            }
        } catch (final IOException e) {
            log.log(Level.SEVERE, LogFacade.SERIALIZING_SESSION_EXCEPTION, new Object[]{session.getIdInternal(), e});
            throw e;
        } finally {
            ((StandardContext) getContainer()).sessionPersistedEndEvent(
                (StandardSession) session);
        }
    }


    // -------------------------------------------------- Lifecycle Methods


    /**
     * Add a lifecycle event listener to this component.
     *
     * @param listener The listener to add
     */
    @Override
    public void addLifecycleListener(final LifecycleListener listener) {

        this.lifecycle.addLifecycleListener(listener);

    }


    /**
     * Gets the (possibly empty) list of lifecycle listeners associated
     * with this session manager.
     */
    @Override
    public List<LifecycleListener> findLifecycleListeners() {
        return this.lifecycle.findLifecycleListeners();
    }


    /**
     * Remove a lifecycle event listener from this component.
     *
     * @param listener The listener to remove
     */
    @Override
    public void removeLifecycleListener(final LifecycleListener listener) {

        this.lifecycle.removeLifecycleListener(listener);

    }


    /**
     * Prepare for the beginning of active use of the public methods of this
     * component.  This method should be called after <code>configure()</code>,
     * and before any of the public methods of the component are utilized.
     *
     * @throws LifecycleException if this component detects a fatal error
     *                            that prevents this component from being used
     */
    @Override
    public void start() throws LifecycleException {

        // Validate and update our current component state
        if (this.started) {
            if (log.isLoggable(Level.INFO)) {
                log.log(Level.INFO, LogFacade.MANAGER_STARTED_INFO);
            }
            return;
        }
        if (!this.initialized) {
            init();
        }

        this.lifecycle.fireLifecycleEvent(START_EVENT, null);
        this.started = true;

        // Force initialization of the random number generator
        if (log.isLoggable(Level.FINEST)) {
            log.log(Level.FINEST, "Force random number initialization starting");
        }
        generateSessionId();
        if (log.isLoggable(Level.FINEST)) {
            log.log(Level.FINEST, "Force random number initialization completed");
        }

        if (this.store == null) {
            log.log(Level.SEVERE, LogFacade.NO_STORE_CONFIG_EXCEPTION);
        } else if (this.store instanceof Lifecycle) {
            ((Lifecycle) this.store).start();
        }

    }


    /**
     * Gracefully terminate the active use of the public methods of this
     * component.  This method should be the last one called on a given
     * instance of this component.
     *
     * @throws LifecycleException if this component detects a fatal error
     *                            that needs to be reported
     */
    @Override
    public void stop() throws LifecycleException {

        if (log.isLoggable(Level.FINE)) {
            log.log(Level.FINE, "Stopping");
        }

        // Validate and update our current component state
        if (!isStarted()) {
            if (log.isLoggable(Level.INFO)) {
                log.log(Level.INFO, LogFacade.MANAGER_NOT_STARTED_INFO);
            }
            return;
        }

        this.lifecycle.fireLifecycleEvent(STOP_EVENT, null);
        setStopped();

        if (getStore() != null && this.saveOnRestart) {
            unload();
        } else {
            // Expire all active sessions
            final Session[] sessions = findSessions();
            for (final Session session1 : sessions) {
                final StandardSession session = (StandardSession) session1;
                if (!session.isValid()) {
                    continue;
                }
                session.expire();
            }
        }

        if (getStore() instanceof Lifecycle) {
            ((Lifecycle) getStore()).stop();
        }

        // Require a new random number generator if we are restarted
        resetRandom();

        if (this.initialized) {
            destroy();
        }

    }


    // ----------------------------------------- PropertyChangeListener Methods


    /**
     * Process property change events from our associated Context.
     *
     * @param event The property change event that has occurred
     */
    @Override
    public void propertyChange(final PropertyChangeEvent event) {

        // Validate the source of this event
        if (!(event.getSource() instanceof Context)) {
            return;
        }

        // Process a relevant property change
        if (event.getPropertyName().equals("sessionTimeout")) {
            try {
                setMaxInactiveIntervalSeconds(((Integer) event.getNewValue()) * 60);
            } catch (final NumberFormatException e) {
                log.log(Level.SEVERE, LogFacade.INVALID_SESSION_TIMEOUT_SETTING_EXCEPTION, event.getNewValue().toString());
            }
        }

    }


    // -------------------------------------------------------- Private Methods


    /**
     * Swap idle sessions out to Store if they are idle too long.
     */
    protected void processMaxIdleSwaps() {

        if (!isStarted() || this.maxIdleSwap < 0) {
            return;
        }

        final Session[] sessions = findSessions();
        final long timeNow = System.currentTimeMillis();

        // Swap out all sessions idle longer than maxIdleSwap
        // FIXME: What's preventing us from mangling a session during
        // a request?
        if (this.maxIdleSwap >= 0) {
            for (final Session session1 : sessions) {
                final StandardSession session = (StandardSession) session1;
                if (!session.isValid()) {
                    continue;
                }
                final int timeIdle = // Truncate, do not round up
                    (int) ((timeNow - session.getLastAccessedTime()) / 1000L);
                if (timeIdle > this.maxIdleSwap && timeIdle > this.minIdleSwap) {
                    if (log.isLoggable(Level.FINE)) {
                        log.log(Level.FINE, LogFacade.SWAPPING_SESSION_TO_STORE, new Object[]{session.getIdInternal(), timeIdle});
                    }
                    try {
                        swapOut(session);
                    } catch (final IOException e) {
                        // This is logged in writeSession()
                    }
                }
            }
        }

    }


    /**
     * Swap idle sessions out to Store if too many are active
     * Hercules: modified method
     */
    protected void processMaxActiveSwaps() {

        if (!isStarted() || getMaxActiveSessions() < 0) {
            return;
        }

        final Session[] sessions = findSessions();

        // FIXME: Smarter algorithm (LRU)
        if (getMaxActiveSessions() >= sessions.length) {
            return;
        }

        if (log.isLoggable(Level.FINE)) {
            log.log(Level.FINE, LogFacade.TOO_MANY_ACTIVE_SESSION, sessions.length);
        }
        int toswap = sessions.length - getMaxActiveSessions();
        final long timeNow = System.currentTimeMillis();

        for (int i = 0; i < sessions.length && toswap > 0; i++) {
            final int timeIdle = // Truncate, do not round up
                (int) ((timeNow - sessions[i].getLastAccessedTime()) / 1000L);
            if (timeIdle > this.minIdleSwap) {
                final StandardSession session = (StandardSession) sessions[i];
                //skip the session if it cannot be locked
                if (session.lockBackground()) {
                    if (log.isLoggable(Level.FINE)) {
                        log.log(Level.FINE, LogFacade.SWAP_OUT_SESSION, new Object[]{session.getIdInternal(), timeIdle});
                    }
                    try {
                        swapOut(session);
                    } catch (final Exception e) {
                        // This is logged in writeSession()
                    } finally {
                        session.unlockBackground();
                    }
                    toswap--;
                }
            }
        }

    }


    /**
     * Back up idle sessions.
     * Hercules: modified method
     */
    protected void processMaxIdleBackups() {

        if (!isStarted() || this.maxIdleBackup < 0) {
            return;
        }

        final Session[] sessions = findSessions();
        final long timeNow = System.currentTimeMillis();

        // Back up all sessions idle longer than maxIdleBackup
        if (this.maxIdleBackup >= 0) {
            for (final Session session1 : sessions) {
                final StandardSession session = (StandardSession) session1;
                if (!session.isValid()) {
                    continue;
                }

                final int timeIdle = // Truncate, do not round up
                    (int) ((timeNow - session.getLastAccessedTime()) / 1000L);
                if (timeIdle > this.maxIdleBackup && session.lockBackground()) {
                    //if session cannot be background locked then skip it
                    if (log.isLoggable(Level.FINE)) {
                        log.log(Level.FINE, LogFacade.BACKUP_SESSION_TO_STORE, new Object[]{session.getIdInternal(), timeIdle});
                    }
                    try {
                        writeSession(session);
                    } catch (final Exception e1) {
                        // This is logged in writeSession()
                    } finally {
                        session.unlockBackground();
                    }
                }
            }
        }

    }
}
