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
 */
// Portions Copyright [2017] [Payara Foundation and/or its affiliates]


/*
 * ReplicationWebEventPersistentManager.java
 *
 * Created on November 18, 2005, 3:38 PM
 *
 */

package org.glassfish.web.ha.session.management;

import fish.payara.nucleus.hazelcast.HazelcastCore;
import org.apache.catalina.Session;
import org.glassfish.ha.common.GlassFishHAReplicaPredictor;
import org.glassfish.ha.common.HACookieInfo;
import org.glassfish.ha.common.HACookieManager;
import org.glassfish.ha.common.NoopHAReplicaPredictor;
import org.glassfish.ha.store.api.BackingStoreConfiguration;
import org.glassfish.ha.store.api.BackingStoreException;
import org.glassfish.ha.store.api.BackingStoreFactory;
import org.glassfish.ha.store.api.Storeable;
import org.glassfish.hk2.api.PerLookup;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.web.ha.LogFacade;
import org.jvnet.hk2.annotations.Service;

import javax.inject.Inject;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.util.Map;
import java.util.logging.Level;

/**
 * @author Rajiv Mordani
 */
@Service
@PerLookup
public class ReplicationWebEventPersistentManager<T extends Storeable> extends ReplicationManagerBase<T>
    implements WebEventPersistentManager {

    @Inject
    private ServiceLocator services;

    private GlassFishHAReplicaPredictor predictor;

    private String clusterName = "";

    private String instanceName = "";


    /**
     * The descriptive information about this implementation.
     */
    private final String info = "ReplicationWebEventPersistentManager/1.0";


    /**
     * The descriptive name of this Manager implementation (for logging).
     */
    private final String name = "ReplicationWebEventPersistentManager";


    // ------------------------------------------------------------- Properties


    /**
     * Return descriptive information about this Manager implementation and
     * the corresponding version number, in the format
     * <code>&lt;description&gt;/&lt;version&gt;</code>.
     */
    @Override
    public String getInfo() {
        return this.info;
    }

    /**
     * Creates a new instance of ReplicationWebEventPersistentManager
     */
    public ReplicationWebEventPersistentManager() {
        if (this._logger.isLoggable(Level.FINE)) {
            this._logger.fine("ReplicationWebEventPersistentManager created");
        }
    }

    @Override
    public void add(final Session session) {
        if (!(session instanceof HANonStorableSession)) {
            super.add(session);
        }
    }

    /**
     * called from valve; does the save of session
     *
     * @param session The session to store
     */
    @Override
    public void doValveSave(final Session session) {
        if (session instanceof HANonStorableSession) {
            return;
        }
        if (this._logger.isLoggable(Level.FINE)) {
            this._logger.fine("in doValveSave");
        }

        try {
            final ReplicationStore replicationStore = (ReplicationStore) this.getStore();
            replicationStore.doValveSave(session);
            if (this._logger.isLoggable(Level.FINE)) {
                this._logger.fine("FINISHED repStore.valveSave");
            }
        } catch (final Exception ex) {
            this._logger.log(Level.FINE, "exception occurred in doValveSave id=" + session.getIdInternal(),
                ex);

        }
    }


    //START OF 6364900
    @Override
    public void postRequestDispatcherProcess(final ServletRequest request, final ServletResponse response) {
        final Session sess = this.getSession(request);

        if (sess != null) {
            doValveSave(sess);
        }
    }

    private Session getSession(final ServletRequest request) {
        final javax.servlet.http.HttpServletRequest httpReq =
            (javax.servlet.http.HttpServletRequest) request;
        final javax.servlet.http.HttpSession httpSess = httpReq.getSession(false);
        if (httpSess == null) {
            return null;
        }
        final String id = httpSess.getId();
        Session sess = null;
        try {
            sess = this.findSession(id);
        } catch (final java.io.IOException ex) {
        }

        return sess;
    }
    //END OF 6364900 

    /**
     * Return the descriptive short name of this Manager implementation.
     */
    @Override
    public String getName() {
        return this.name;
    }

    /**
     * Back up idle sessions.
     * Hercules: modified method we do not want
     * background saves when we are using web-event persistence-frequency
     */
    @Override
    protected void processMaxIdleBackups() {
        //this is a deliberate no-op for this manager
    }

    /**
     * Swap idle sessions out to Store if too many are active
     * Hercules: modified method
     */
    @Override
    protected void processMaxActiveSwaps() {
        //this is a deliberate no-op for this manager
    }

    /**
     * Swap idle sessions out to Store if they are idle too long.
     */
    @Override
    protected void processMaxIdleSwaps() {
        //this is a deliberate no-op for this manager
    }

    @Override
    public String getReplicaFromPredictor(final String sessionId, final String oldJreplicaValue) {
        if (isDisableJreplica()) {
            return null;
        }
        String gmsClusterName = "";
        final HazelcastCore hazelcast = this.services.getService(HazelcastCore.class);
        if (hazelcast.isEnabled()) {
            gmsClusterName = hazelcast.getMemberGroup();
        }
        final HACookieInfo cookieInfo = this.predictor.makeCookie(gmsClusterName, sessionId, oldJreplicaValue);
        HACookieManager.setCurrrent(cookieInfo);
        return cookieInfo.getNewReplicaCookie();
    }


    @Override
    public void createBackingStore(final String persistenceType, final String storeName, final Class<T> metadataClass, final Map<String, Object> vendorMap) {
        if (this._logger.isLoggable(Level.FINE)) {
            this._logger.fine("Create backing store invoked with persistence type " + persistenceType + " and store name " + storeName);
        }
        final BackingStoreFactory factory = this.services.getService(BackingStoreFactory.class, persistenceType);
        final BackingStoreConfiguration<String, T> conf = new BackingStoreConfiguration<>();

        final HazelcastCore hazelcast = this.services.getService(HazelcastCore.class);
        if (hazelcast.isEnabled()) {
            this.clusterName = hazelcast.getMemberGroup();
            this.instanceName = hazelcast.getMemberName();
        }

        conf.setStoreName(storeName)
            .setClusterName(this.clusterName)
            .setInstanceName(this.instanceName)
            .setStoreType(persistenceType)
            .setKeyClazz(String.class).setValueClazz(metadataClass)
            .setClassLoader(this.getClass().getClassLoader());
        if (vendorMap != null) {
            conf.getVendorSpecificSettings().putAll(vendorMap);
        }

        try {
            if (this._logger.isLoggable(Level.FINE)) {
                this._logger.fine("About to create backing store " + conf);
            }
            this.backingStore = factory.createBackingStore(conf);
        } catch (final BackingStoreException e) {
            this._logger.log(Level.WARNING, LogFacade.COULD_NOT_CREATE_BACKING_STORE, e);
        }
        final Object obj = conf.getVendorSpecificSettings().get("key.mapper");
        if (obj instanceof GlassFishHAReplicaPredictor) {
            this.predictor = (GlassFishHAReplicaPredictor) obj;
            if (this._logger.isLoggable(Level.FINE)) {
                this._logger.fine("ReplicatedManager.keymapper is " + this.predictor);
            }
        } else {
            this.predictor = new NoopHAReplicaPredictor();
        }
    }
}
