/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2012 Oracle and/or its affiliates. All rights reserved.
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
// Portions Copyright [2016-2017] [Payara Foundation and/or its affiliates]

package org.glassfish.web.ha.session.management;

import com.sun.enterprise.container.common.spi.util.JavaEEIOUtils;
import org.apache.catalina.Container;
import org.apache.catalina.Loader;
import org.apache.catalina.Session;
import org.glassfish.ha.store.api.BackingStore;
import org.glassfish.ha.store.api.BackingStoreException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;

/**
 * @author Larry White
 * @author Rajiv Mordani
 */
public class ReplicationAttributeStore extends ReplicationStore {


    /**
     * Creates a new instance of ReplicationAttributeStore
     */
    public ReplicationAttributeStore(final JavaEEIOUtils ioUtils) {
        super(ioUtils);
        setLogLevel();
    }

    // HAStorePoolElement methods begin

    /**
     * Save the specified Session into this Store.  Any previously saved
     * information for the associated session identifier is replaced.
     *
     * @param session Session to be saved
     * @throws IOException if an input/output error occurs
     */
    @Override
    public void valveSave(final Session session) throws IOException {
        if (!(session instanceof HASession)) {
            return;
        }
        final HASession haSess = (HASession) session;
        if (haSess.isPersistent() && !haSess.isDirty()) {
            this.updateLastAccessTime(session);
        } else {
            this.doValveSave(session);
            haSess.setPersistent(true);
        }
        haSess.setDirty(false);
    }

    // Store method begin

    /**
     * Save the specified Session into this Store.  Any previously saved
     * information for the associated session identifier is replaced.
     *
     * @param session Session to be saved
     * @throws IOException if an input/output error occurs
     */
    @Override
    public void save(final Session session) throws IOException {
        if (!(session instanceof HASession)) {
            return;
        }
        final HASession haSess = (HASession) session;
        if (haSess.isPersistent() && !haSess.isDirty()) {
            this.updateLastAccessTime(session);
        } else {
            this.doSave(session);
            haSess.setPersistent(true);
        }
        haSess.setDirty(false);
    }


    /**
     * Save the specified Session into this Store.  Any previously saved
     * information for the associated session identifier is replaced.
     *
     * @param session Session to be saved
     * @throws IOException if an input/output error occurs
     */
    @Override
    public void doValveSave(final Session session) throws IOException {
        if (_logger.isLoggable(Level.FINE)) {
            _logger.fine("ReplicationAttributeStore>>doValveSave:valid =" + session.getIsValid());
            if (session instanceof HASession) {
                _logger.fine("ReplicationAttributeStore>>valveSave:ssoId=" + session.getSsoId());
            }
        }

        // begin 6470831 do not save if session is not valid
        if (!session.getIsValid()) {
            return;
        }
        // end 6470831

        if (!(session instanceof ModifiedAttributeHASession)) {
            return;
        }

        final ModifiedAttributeHASession modAttrSession
            = (ModifiedAttributeHASession) session;

        if (session.getPrincipal() != null) {
            final String userName = session.getPrincipal().getName();
            ((BaseHASession) session).setUserName(userName);
        }

        final BackingStore<String, CompositeMetadata> replicator = getCompositeMetadataBackingStore();
        if (_logger.isLoggable(Level.FINE)) {
            _logger.fine("ReplicationAttributeStore>>save: replicator: " + replicator);
        }
        final CompositeMetadata compositeMetadata
            = createCompositeMetadata(modAttrSession);

        try {
            if (_logger.isLoggable(Level.FINE)) {
                _logger.fine("CompositeMetadata is " + compositeMetadata + " id is " + session.getIdInternal());
            }

            replicator.save(
                session.getIdInternal(),
                compositeMetadata,
                !((HASession) session).isPersistent()
            );
            modAttrSession.resetAttributeState();
            postSaveUpdate(modAttrSession);
        } catch (final BackingStoreException ex) {
            if (_logger.isLoggable(Level.SEVERE)) {
                _logger.severe("doValveSave failed " + ex);
            }
        }
    }


    /**
     * Save the specified Session into this Store.  Any previously saved
     * information for the associated session identifier is replaced.
     *
     * @param session Session to be saved
     * @throws IOException if an input/output error occurs
     */
    @Override
    public void doSave(final Session session) throws IOException {
        // begin 6470831 do not save if session is not valid
        if (!session.getIsValid()) {
            return;
        }

        if (!(session instanceof ModifiedAttributeHASession)) {
            return;
        }

        // end 6470831        
        final ModifiedAttributeHASession modAttrSession
            = (ModifiedAttributeHASession) session;
        final BackingStore<String, CompositeMetadata> replicator = getCompositeMetadataBackingStore();
        if (_logger.isLoggable(Level.FINE)) {
            _logger.fine("ReplicationAttributeStore>>doSave: replicator: " + replicator);
        }
        final CompositeMetadata compositeMetadata
            = createCompositeMetadata(modAttrSession);

        try {
            if (_logger.isLoggable(Level.FINE)) {
                _logger.fine("CompositeMetadata is " + compositeMetadata + " id is " + session.getIdInternal());
            }

            replicator.save(session.getIdInternal(), //id
                compositeMetadata, !((HASession) session).isPersistent());
            modAttrSession.resetAttributeState();
            postSaveUpdate(modAttrSession);
        } catch (final BackingStoreException ex) {
            if (_logger.isLoggable(Level.SEVERE)) {
                _logger.severe("doSave failed " + ex);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private BackingStore<String, CompositeMetadata> getCompositeMetadataBackingStore() {
        final ReplicationManagerBase<CompositeMetadata> mgr
            = (ReplicationManagerBase<CompositeMetadata>) this.getManager();
        return mgr.getBackingStore();
    }

    @Override
    public Session load(final String id, final String version)
        throws IOException {
        try {
            final CompositeMetadata metaData =
                getCompositeMetadataBackingStore().load(id, version);
            if (_logger.isLoggable(Level.FINE)) {
                _logger.fine("ReplicationAttributeStore>>load:id=" + id + ", metaData=" + metaData);
            }
            final Session session = getSession(metaData);
            validateAndSave(session);
            return session;
        } catch (final BackingStoreException ex) {
            throw new IOException("Error during load: " + ex.getMessage(), ex);
        }
    }

    private void validateAndSave(final Session session) throws IOException {
        if (session != null) {
            if (_logger.isLoggable(Level.FINE)) {
                _logger.fine("ReplicationAttributeStore>>validateAndSave saving " +
                    "the session after loading it. Session=" + session);
            }
            //save session - save will reset dirty to false
            ((HASession) session).setDirty(true);
            valveSave(session); // TODO :: revisit this for third party backing stores;
        }
        if (session != null) {
            ((HASession) session).setDirty(false);
            ((HASession) session).setPersistent(false);
        }
    }


    public Session getSession(final CompositeMetadata metadata)
        throws IOException {
        if (metadata == null || metadata.getState() == null) {
            return null;
        }
        final byte[] state = metadata.getState();
        Session session = null;
        Loader loader = null;
        ClassLoader classLoader = null;
        ObjectInputStream ois = null;
        final Container container = this.manager.getContainer();
        final String ssoId;
        final long version;

        try {
            final ByteArrayInputStream bais = new ByteArrayInputStream(state);
            BufferedInputStream bis = new BufferedInputStream(bais);

            //Get the username, ssoId from metadata
            ssoId = metadata.getStringExtraParam();
            version = metadata.getVersion();

            if (_logger.isLoggable(Level.FINEST)) {
                _logger.finest("loaded session from replicationstore, length = " + state.length);
            }
            if (container != null) {
                loader = container.getLoader();
            }

            if (loader != null) {
                classLoader = loader.getClassLoader();
            }

            if (classLoader != null) {

                try {
                    ois = this.ioUtils.createObjectInputStream(bis, true, classLoader, getUniqueId());
                } catch (final Exception ex) {
                }

            }
            if (ois == null) {
                ois = new ObjectInputStream(bis);
            }

            if (ois != null) {
                try {
                    session = readSession(this.manager, ois);
                } finally {

                    try {
                        ois.close();
                        bis = null;
                    } catch (final IOException e) {
                    }
                }
            }
        } catch (final ClassNotFoundException e) {
            throw new IOException("Error during deserialization: " + e.getMessage(), e);
        }

        final String username = ((HASession) session).getUserName();
        if ((username != null) && (!username.equals("")) && session.getPrincipal() == null) {
            if (this._debug > 0) {
                debug("Username retrieved is " + username);
            }
            final java.security.Principal pal =
                ((com.sun.web.security.RealmAdapter) container.getRealm()).createFailOveredPrincipal(username);
            if (this._debug > 0) {
                debug("principal created using username  " + pal);
            }
            if (pal != null) {
                session.setPrincipal(pal);
                if (this._debug > 0) {
                    debug("getSession principal=" + pal + " was added to session=" + session);
                }
            }
        }
        //--SRI        

        session.setNew(false);
        if (_logger.isLoggable(Level.FINE)) {
            _logger.fine("ReplicationAttributeStore>>ssoId=" + ssoId);
        }

        ((HASession) session).setVersion(version);
        ((HASession) session).setDirty(false);

        //now load entries from deserialized entries collection
        ((ModifiedAttributeHASession) session).clearAttributeStates();
        final byte[] entriesState = metadata.getState();
        if (entriesState != null) {
            final Collection<?> entries = this.deserializeStatesCollection(entriesState);
            loadAttributes((ModifiedAttributeHASession) session, entries);
        }
        loadAttributes((ModifiedAttributeHASession) session, metadata.getEntries());
        return session;
    }


    //metadata related

    private void postSaveUpdate(final ModifiedAttributeHASession modAttrSession) {
        if (_logger.isLoggable(Level.FINE)) {
            _logger.fine("ReplicationAttributeStore>>postSaveUpdate");
        }
        final List<String> addedAttrs = modAttrSession.getAddedAttributes();
        final List<String> modifiedAttrs = modAttrSession.getModifiedAttributes();
        final List<String> deletedAttrs = modAttrSession.getDeletedAttributes();
        printAttrList("ADDED", addedAttrs);
        printAttrList("MODIFIED", modifiedAttrs);
        printAttrList("DELETED", deletedAttrs);

        postProcessSetAttrStates(modAttrSession, addedAttrs);
        postProcessSetAttrStates(modAttrSession, modifiedAttrs);

    }

    private void postProcessSetAttrStates(final ModifiedAttributeHASession modAttrSession, final List<String> attrsList) {
        for (final String nextStateName : attrsList) {
            modAttrSession.setAttributeStatePersistent(nextStateName, true);
            modAttrSession.setAttributeStateDirty(nextStateName, false);
        }
    }

    private CompositeMetadata createCompositeMetadata(final ModifiedAttributeHASession modAttrSession) throws IOException {

        byte[] trunkState = null;
        if (!modAttrSession.isNew()) {
            try {
                trunkState = this.getByteArray(modAttrSession);
            } catch (final IOException ex) {
                if (ex instanceof NotSerializableException) {
                    throw ex;
                }
            }
        }
        if (_logger.isLoggable(Level.FINE)) {
            _logger.fine("ReplicationAttributeStore>>createCompositeMetadata:trunkState=" + trunkState);
        }

        final List<SessionAttributeMetadata> entries = new ArrayList<SessionAttributeMetadata>();
        final List<String> addedAttrs = modAttrSession.getAddedAttributes();
        final List<String> modifiedAttrs = modAttrSession.getModifiedAttributes();
        final List<String> deletedAttrs = modAttrSession.getDeletedAttributes();
        printAttrList("ADDED", addedAttrs);
        printAttrList("MODIFIED", modifiedAttrs);
        printAttrList("DELETED", deletedAttrs);

        addToEntries(modAttrSession, entries,
            SessionAttributeMetadata.Operation.ADD, addedAttrs);
        addToEntries(modAttrSession, entries,
            SessionAttributeMetadata.Operation.UPDATE, modifiedAttrs);
        addToEntries(modAttrSession, entries,
            SessionAttributeMetadata.Operation.DELETE, deletedAttrs);

        return new CompositeMetadata(
            modAttrSession.getVersion(),
            modAttrSession.getLastAccessedTimeInternal(),
            modAttrSession.getMaxInactiveInterval() * 1000L,
            entries,
            trunkState,
            null
        );
    }

    private void printAttrList(final String attrListType, final List<String> attrList) {
        if (_logger.isLoggable(Level.FINE)) {
            _logger.fine("AttributeType = " + attrListType);
            String nextAttrName = null;
            for (int i = 0; i < attrList.size(); i++) {
                nextAttrName = attrList.get(i);
                _logger.fine("attribute[" + i + "]=" + nextAttrName);
            }
        }
    }

    private void addToEntries(final ModifiedAttributeHASession modAttrSession,
                              final List<SessionAttributeMetadata> entries, final SessionAttributeMetadata.Operation op,
                              final List<String> attrList) {
        for (final String nextAttrName : attrList) {
            final Object nextAttrValue = modAttrSession.getAttribute(nextAttrName);
            byte[] nextValue = null;
            try {
                nextValue = getByteArray(nextAttrValue);
            } catch (final IOException e) {
                if (_logger.isLoggable(Level.SEVERE)) {
                    _logger.severe("addToEntries failed " + e);
                }
            }
            final SessionAttributeMetadata nextAttrMetadata
                = new SessionAttributeMetadata(nextAttrName, op, nextValue);
            entries.add(nextAttrMetadata);
        }
    }

    /**
     * Create an byte[] for the session that we can then pass to
     * the HA Store.
     *
     * @param attributeValue The attribute value we are serializing
     */
    protected byte[] getByteArray(final Object attributeValue)
        throws IOException {
        ByteArrayOutputStream bos = null;
        ObjectOutputStream oos = null;


        byte[] obs;
        try {
            bos = new ByteArrayOutputStream();


            try {
                oos = this.ioUtils.createObjectOutputStream(new BufferedOutputStream(bos), true);
            } catch (final Exception ex) {
            }

            //use normal ObjectOutputStream if there is a failure during stream creation
            if (oos == null) {
                oos = new ObjectOutputStream(new BufferedOutputStream(bos));
            }
            oos.writeObject(attributeValue);
            oos.close();
            oos = null;

            obs = bos.toByteArray();
        } finally {
            if (oos != null) {
                oos.close();
            }
        }

        return obs;
    }

    /**
     * Given a byte[] containing session data, return a session
     * object
     *
     * @param state The byte[] with the session attribute data
     * @return A newly created object for the given session attribute data
     */
    protected Object getAttributeValue(final byte[] state)
        throws IOException, ClassNotFoundException {
        Object attributeValue = null;
        BufferedInputStream bis = null;
        ByteArrayInputStream bais = null;
        Loader loader = null;
        ClassLoader classLoader = null;
        ObjectInputStream ois = null;
        final Container container = this.manager.getContainer();


        try {
            bais = new ByteArrayInputStream(state);
            bis = new BufferedInputStream(bais);

            if (container != null) {
                loader = container.getLoader();
            }

            if (loader != null) {
                classLoader = loader.getClassLoader();
            }

            if (classLoader != null) {

                try {
                    ois = this.ioUtils.createObjectInputStream(bis, true, classLoader, getUniqueId());
                } catch (final Exception ex) {
                }

            }
            if (ois == null) {
                ois = new ObjectInputStream(bis);
            }

            if (ois != null) {
                try {
                    attributeValue = ois.readObject();
                } finally {

                    try {
                        ois.close();
                        bis = null;
                    } catch (final IOException e) {
                    }

                }
            }
        } catch (final ClassNotFoundException e) {

            if (_logger.isLoggable(Level.FINE)) {
                _logger.log(Level.FINE, "ClassNotFoundException occurred in getAttributeValue", e);
            }
            throw e;
        } catch (final IOException e) {
            throw e;
        }

        return attributeValue;
    }

    //new serialization code for Collection

    /**
     * Create an byte[] for the session that we can then pass to
     * the HA Store.
     *
     * @param entries The Collection of entries we are serializing
     */
    protected byte[] getByteArrayFromCollection(final Collection entries)
        throws IOException {
        ByteArrayOutputStream bos = null;
        ObjectOutputStream oos = null;


        byte[] obs;
        try {
            bos = new ByteArrayOutputStream();


            try {
                oos = this.ioUtils.createObjectOutputStream(new BufferedOutputStream(bos), true);
            } catch (final Exception ex) {
            }

            //use normal ObjectOutputStream if there is a failure during stream creation
            if (oos == null) {
                oos = new ObjectOutputStream(new BufferedOutputStream(bos));
            }
            //first write out the entriesSize
            final int entriesSize = entries.size();
            oos.writeObject(Integer.valueOf(entriesSize));
            //then write out the entries
            final Iterator it = entries.iterator();
            while (it.hasNext()) {
                oos.writeObject(it.next());
            }
            oos.close();
            oos = null;

            obs = bos.toByteArray();
        } finally {
            if (oos != null) {
                oos.close();
            }
        }

        return obs;
    }

    /**
     * Given a byte[] containing session data, return a session
     * object
     *
     * @param state The byte[] with the session attribute data
     * @return A newly created object for the given session attribute data
     */
    protected Object getAttributeValueCollection(final byte[] state)
        throws IOException, ClassNotFoundException {
        final Collection<Object> attributeValueList = new ArrayList<Object>();
        BufferedInputStream bis = null;
        ByteArrayInputStream bais = null;
        Loader loader = null;
        ClassLoader classLoader = null;
        ObjectInputStream ois = null;
        final Container container = this.manager.getContainer();

        try {
            bais = new ByteArrayInputStream(state);
            bis = new BufferedInputStream(bais);

            if (container != null) {
                loader = container.getLoader();
            }

            if (loader != null) {
                classLoader = loader.getClassLoader();
            }

            if (classLoader != null) {

                try {
                    ois = this.ioUtils.createObjectInputStream(bis, true, classLoader, getUniqueId());
                } catch (final Exception ex) {
                }

            }
            if (ois == null) {
                ois = new ObjectInputStream(bis);
            }
            if (ois != null) {
                try {
                    //first get List size
                    final Object whatIsIt = ois.readObject();
                    int entriesSize = 0;
                    if (whatIsIt instanceof Integer) {
                        entriesSize = ((Integer) whatIsIt).intValue();
                    }
                    for (int i = 0; i < entriesSize; i++) {
                        final Object nextAttributeValue = ois.readObject();
                        attributeValueList.add(nextAttributeValue);
                    }
                } finally {
                    try {
                        ois.close();
                        bis = null;
                    } catch (final IOException e) {
                    }
                }
            }
        } catch (final ClassNotFoundException e) {
            if (_logger.isLoggable(Level.FINE)) {
                _logger.log(Level.FINE, "ClassNotFoundException occurred in getAttributeValueCollection", e);
            }
            throw e;
        } catch (final IOException e) {
            throw e;
        }

        return attributeValueList;
    }

    //end new serialization code for Collection

    /**
     * Given a session, load its attributes
     *
     * @param modifiedAttributeSession The session (header info only) having its attributes loaded
     * @param attributeList            The List<AttributeMetadata> list of loaded attributes
     */
    protected void loadAttributes(final ModifiedAttributeHASession modifiedAttributeSession,
                                  final Collection<?> attributeList) {
        if (_logger.isLoggable(Level.FINEST)) {
            _logger.finest("in loadAttributes -- ReplicationAttributeStore : session id=" + modifiedAttributeSession.getIdInternal());
        }

        for (final Object o : attributeList) {
            final SessionAttributeMetadata nextAttrMetadata = (SessionAttributeMetadata) o;
            final String thisAttrName = nextAttrMetadata.getAttributeName();
            final byte[] nextAttrState = nextAttrMetadata.getState();
            try {
                final Object thisAttrVal = getAttributeValue(nextAttrState);
                if (thisAttrVal != null) {
                    if (_logger.isLoggable(Level.FINEST)) {
                        _logger.finest("Setting Attribute: " + thisAttrName);
                    }
                    modifiedAttributeSession.setAttribute(thisAttrName, thisAttrVal);
                    modifiedAttributeSession.setAttributeStatePersistent(thisAttrName, false);
                    modifiedAttributeSession.setAttributeStateDirty(thisAttrName, false);
                }
            } catch (final ClassNotFoundException | IOException e) {
                if (_logger.isLoggable(Level.SEVERE)) {
                    _logger.severe("loadAttributes failed " + e);
                }
            }

            if (_logger.isLoggable(Level.FINEST)) {
                _logger.finest("Attr retrieved======" + thisAttrName);
            }
        }
    }

    private Collection<?> deserializeStatesCollection(final byte[] entriesState) {
        Collection<?> result = Collections.emptyList();

        try {
            result = (Collection<?>) getAttributeValueCollection(entriesState);
        } catch (final ClassNotFoundException | IOException e) {
            if (_logger.isLoggable(Level.SEVERE)) {
                _logger.severe("loadAttributes failed " + e);
            }
        }

        return result;
    }
}
