/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2011 Oracle and/or its affiliates. All rights reserved.
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
// Portions Copyright [2016-2019] [Payara Foundation and/or its affiliates]

/*
 * BaseHASession.java
 *
 * Created on October 23, 2003, 11:20 AM
 */

package org.glassfish.web.ha.session.management;

import org.apache.catalina.Globals;
import org.apache.catalina.Manager;
import org.apache.catalina.session.StandardSession;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.Principal;
import java.util.Enumeration;

/**
 * @author lwhite
 * @author Rajiv Mordani
 */
abstract class BaseHASession extends StandardSession implements HASession {

    private String userName = "";
    private boolean persistentFlag = false;

    /**
     * Creates a new instance of BaseHASession
     */
    BaseHASession(final Manager manager) {
        super(manager);
    }

    /**
     * Set the session identifier for this session.
     *
     * @param id The new session identifier
     */
    @Override
    public void setId(final String id) {
        super.setId(id);

        // Set the jreplica value for the first request here when the session is created - after that it is done in the valve
        final ReplicationManagerBase manager = (ReplicationManagerBase) (getManager());
        final String jReplicaValue = manager.getReplicaFromPredictor(id, null);
        if (jReplicaValue != null) {
            setNote(Globals.JREPLICA_SESSION_NOTE, jReplicaValue);
        }

    }

    /**
     * is the session persistent
     */
    @Override
    public boolean isPersistent() {
        return this.persistentFlag;
    }

    /**
     * this sets the persistent flag
     */
    @Override
    public void setPersistent(final boolean value) {
        this.persistentFlag = value;
    }

    /**
     * this returns the user name
     */
    @Override
    public String getUserName() {
        return this.userName;
    }

    /**
     * this sets the user name
     */
    @Override
    public void setUserName(final String value) {
        this.userName = value;
    }

    /**
     * Overriding the setPrincipal of StandardSession
     *
     * @param principal The new Principal, or <code>null</code> if none
     */
    @Override
    public void setPrincipal(final Principal principal) {
        super.setPrincipal(principal);
        this.setDirty(true);
    }

    @Override
    public void recycle() {
        super.recycle();
        this.userName = "";
        this.ssoId = "";
        this.persistentFlag = false;
    }

    /**
     * Read a serialized version of this session object from the specified
     * object input stream.
     * <p>
     * <b>IMPLEMENTATION NOTE</b>:  The reference to the owning Manager
     * is not restored by this method, and must be set explicitly.
     *
     * @param stream The input stream to read from
     * @throws ClassNotFoundException if an unknown class is specified
     * @throws IOException            if an input/output error occurs
     */
    private void readObject(final ObjectInputStream stream)
        throws ClassNotFoundException, IOException {

        // Deserialize the scalar instance variables (except Manager)
        this.userName = (String) stream.readObject();
    }

    /**
     * Write a serialized version of this session object to the specified
     * object output stream.
     * <p>
     * <b>IMPLEMENTATION NOTE</b>:  The owning Manager will not be stored
     * in the serialized representation of this Session.  After calling
     * <code>readObject()</code>, you must set the associated Manager
     * explicitly.
     * <p>
     * <b>IMPLEMENTATION NOTE</b>:  Any attribute that is not Serializable
     * will be unbound from the session, with appropriate actions if it
     * implements HttpSessionBindingListener.  If you do not want any such
     * attributes, be sure the <code>distributable</code> property of the
     * associated Manager is set to <code>true</code>.
     *
     * @param stream The output stream to write to
     * @throws IOException if an input/output error occurs
     */
    private void writeObject(final ObjectOutputStream stream) throws IOException {

        // Write the scalar instance variables
        stream.writeObject(this.userName);
    }

    /**
     * Return a string representation of this object.
     */
    private CharSequence superToString() {

        final StringBuilder sb = new StringBuilder(1000);
        sb.append("BaseHASession[");
        sb.append(this.id);
        sb.append("]");

        sb.append("\n");
        sb.append("isValid:").append(getIsValid());

        if (getIsValid()) {
            final Enumeration<String> attrNamesEnum = getAttributeNamesInternal();
            while (attrNamesEnum.hasMoreElements()) {
                final String nextAttrName = attrNamesEnum.nextElement();
                final Object nextAttrValue = getAttributeInternal(nextAttrName);
                sb.append("\n");
                sb.append("attrName = ").append(nextAttrName);
                sb.append(" : attrValue = ").append(nextAttrValue);
            }
        }

        return sb;
        // END S1AS
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(1200);
        sb.append(superToString());
        sb.append(" ssoid: ").append(getSsoId());
        sb.append(" userName: ").append(getUserName());
        sb.append(" version: ").append(getVersion());
        sb.append(" persistent: ").append(isPersistent());
        return sb.toString();
    }

}
