/*
************************************************************************
*******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
**************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
*
*  (c) 2020.                            (c) 2020.
*  Government of Canada                 Gouvernement du Canada
*  National Research Council            Conseil national de recherches
*  Ottawa, Canada, K1A 0R6              Ottawa, Canada, K1A 0R6
*  All rights reserved                  Tous droits réservés
*
*  NRC disclaims any warranties,        Le CNRC dénie toute garantie
*  expressed, implied, or               énoncée, implicite ou légale,
*  statutory, of any kind with          de quelque nature que ce
*  respect to the software,             soit, concernant le logiciel,
*  including without limitation         y compris sans restriction
*  any warranty of merchantability      toute garantie de valeur
*  or fitness for a particular          marchande ou de pertinence
*  purpose. NRC shall not be            pour un usage particulier.
*  liable in any event for any          Le CNRC ne pourra en aucun cas
*  damages, whether direct or           être tenu responsable de tout
*  indirect, special or general,        dommage, direct ou indirect,
*  consequential or incidental,         particulier ou général,
*  arising from the use of the          accessoire ou fortuit, résultant
*  software.  Neither the name          de l'utilisation du logiciel. Ni
*  of the National Research             le nom du Conseil National de
*  Council of Canada nor the            Recherches du Canada ni les noms
*  names of its contributors may        de ses  participants ne peuvent
*  be used to endorse or promote        être utilisés pour approuver ou
*  products derived from this           promouvoir les produits dérivés
*  software without specific prior      de ce logiciel sans autorisation
*  written permission.                  préalable et particulière
*                                       par écrit.
*
*  This file is part of the             Ce fichier fait partie du projet
*  OpenCADC project.                    OpenCADC.
*
*  OpenCADC is free software:           OpenCADC est un logiciel libre ;
*  you can redistribute it and/or       vous pouvez le redistribuer ou le
*  modify it under the terms of         modifier suivant les termes de
*  the GNU Affero General Public        la “GNU Affero General Public
*  License as published by the          License” telle que publiée
*  Free Software Foundation,            par la Free Software Foundation
*  either version 3 of the              : soit la version 3 de cette
*  License, or (at your option)         licence, soit (à votre gré)
*  any later version.                   toute version ultérieure.
*
*  OpenCADC is distributed in the       OpenCADC est distribué
*  hope that it will be useful,         dans l’espoir qu’il vous
*  but WITHOUT ANY WARRANTY;            sera utile, mais SANS AUCUNE
*  without even the implied             GARANTIE : sans même la garantie
*  warranty of MERCHANTABILITY          implicite de COMMERCIALISABILITÉ
*  or FITNESS FOR A PARTICULAR          ni d’ADÉQUATION À UN OBJECTIF
*  PURPOSE.  See the GNU Affero         PARTICULIER. Consultez la Licence
*  General Public License for           Générale Publique GNU Affero
*  more details.                        pour plus de détails.
*
*  You should have received             Vous devriez avoir reçu une
*  a copy of the GNU Affero             copie de la Licence Générale
*  General Public License along         Publique GNU Affero avec
*  with OpenCADC.  If not, see          OpenCADC ; si ce n’est
*  <http://www.gnu.org/licenses/>.      pas le cas, consultez :
*                                       <http://www.gnu.org/licenses/>.
*
************************************************************************
*/

package org.opencadc.skaha.session;

import ca.nrc.cadc.util.StringUtil;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;
import org.opencadc.skaha.K8SUtil;

/**
 * Process the GET request on the session(s) or app(s).
 *
 * @author majorb
 */
public class GetAction extends SessionAction {
    
    private static final Logger log = Logger.getLogger(GetAction.class);

    public GetAction() {
        super();
    }

    @Override
    public void doAction() throws Exception {
        super.initRequest();
        if (requestType.equals(REQUEST_TYPE_SESSION)) {
            String view = syncInput.getParameter("view");
            if (sessionID == null) {
                if (SESSION_VIEW_STATS.equals(view)) {
                    // report stats on sessions and resources
                    List<Session> sessions = getAllSessions(null);
                    int desktopCount = filter(sessions, "desktop-app", "Running").size();
                    int headlessCount = filter(sessions, "headless", "Running").size();
                    int totalCount = sessions.size();
                    String k8sNamespace = getNamespace();
                    try {
                        int coresInUse = 0;
                        int coresAvailable = 0;
                        int maxCores = 0;
                        int maxRAM = 0;
                        int withCores = 0;
                        int withRAM = 0;
                        List<String> nodeNames = getNodeNames(k8sNamespace);
                        for (String nodeName : nodeNames) {
                            int rCPUCores = getCPUCores(nodeName, k8sNamespace);
                            int aResources[] = getAvailableResources(nodeName, k8sNamespace);
                            int aCPUCores = aResources[0];
                            if (aCPUCores > maxCores) {
                                maxCores = aCPUCores;
                                withRAM = aResources[1];
                            }
                            
                            int aRAM = aResources[1];
                            if (aRAM > maxRAM) {
                                maxRAM = aRAM;
                                withCores= aCPUCores;
                            }
                            
                            coresInUse = coresInUse + rCPUCores;
                            coresAvailable = coresAvailable + aCPUCores;
                            log.debug("Node: " + nodeName + " Cores: " + rCPUCores + "/" + aCPUCores + " RAM: " + aRAM + " GB");
                        }

                        String withRAMStr = String.valueOf(withRAM) + "Gi";
                        String maxRAMStr = String.valueOf(maxRAM) + "Gi";
                        ResourceStats rc = new ResourceStats(desktopCount, headlessCount, totalCount, coresInUse, coresAvailable, maxCores, withRAMStr, maxRAMStr, withCores);
                        Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
                        String json = gson.toJson(rc);
                        syncOutput.setHeader("Content-Type", "application/json");
                        syncOutput.getOutputStream().write(json.getBytes());
                    } catch (Exception e) {
                        log.error(e);
                        throw new IllegalStateException("failed reading k8s-resources.properties", e);
                    }
                } else {
                    // List the sessions
                    String typeFilter = syncInput.getParameter("type");
                    String statusFilter = syncInput.getParameter("status");
                    boolean allUsers = SESSION_LIST_VIEW_ALL.equals(view);
                
                    String json = listSessions(typeFilter, statusFilter, allUsers);
                
                    syncOutput.setHeader("Content-Type", "application/json");
                    syncOutput.getOutputStream().write(json.getBytes());
                }
            } else {
                if (SESSION_VIEW_LOGS.equals(view)) {
                    // return the container log
                    syncOutput.setHeader("Content-Type", "text/plain");
                    syncOutput.setCode(200);
                    streamContainerLogs(sessionID, syncOutput.getOutputStream());
                } else if (SESSION_VIEW_EVENTS.equals(view)) {
                    // return the event logs
                    String logs = getEventLogs(sessionID);
                    syncOutput.setHeader("Content-Type", "text/plain");
                    syncOutput.getOutputStream().write(logs.getBytes());
                } else {
                    // return the session
                    String json = getSingleSession(sessionID);
                    syncOutput.setHeader("Content-Type", "application/json");
                    syncOutput.getOutputStream().write(json.getBytes());
                }
            }
            return;
        }
        if (requestType.equals(REQUEST_TYPE_APP)) {
            if (appID == null) {
                throw new UnsupportedOperationException("App listing not supported.");
            } else {
                throw new UnsupportedOperationException("App detail viewing not supported.");
            }
        }
    }

    private String getNamespace() {
        try {
            return K8SUtil.getWorkloadNamespace();
        } catch (Exception e) {
            log.error(e);
            throw new IllegalStateException("failed to get workload namespace", e);
        }
    }

    private List<String> getNodeNames(String k8sNamespace) throws Exception {
        String getNodeNamesCmd = "kubectl -n " + k8sNamespace + " get nodes -o custom-columns=:metadata.name";
        String nodeNames = execute(getNodeNamesCmd.split(" "));
        log.debug("nodes: " + nodeNames);
        if (nodeNames != null) {
            String[] lines = nodeNames.split("\n");
            if (lines.length > 0) {
                return Arrays.asList(lines);
            }
        }
        
        return new ArrayList<String>();
    }
    
    private int getCPUCores(String nodeName, String k8sNamespace) throws Exception {
        String getCPUCoresCmd = "kubectl -n " + k8sNamespace + " get pods -o custom-columns=0:.spec.containers[].resources.requests.cpu --field-selector spec.nodeName=" + nodeName;
        String nodeCPUCores = execute(getCPUCoresCmd.split(" "));
        log.debug("CPU cores in node " + nodeName + ": " + nodeCPUCores);
        if (nodeCPUCores != null) {
            String[] lines = nodeCPUCores.split("\n");
            if (lines.length > 0) {
                List<Integer> cpuCores = Arrays.stream(lines).map(Integer::parseInt).collect(Collectors.toList());
                int totalNodeCPUCores = 0;
                for (Integer cpuCore : cpuCores) {
                    totalNodeCPUCores = totalNodeCPUCores + cpuCore;
                }
                
                return totalNodeCPUCores;
            }
        }
        
        return 0;
    }

    private int[] getAvailableResources(String nodeName, String k8sNamespace) throws Exception {
        int resources[] = new int[2];
        String getCPUCoresCmd = "kubectl -n " + k8sNamespace + " describe node " + nodeName;
        String nodeCPUCores = execute(getCPUCoresCmd.split(" "));
        if (nodeCPUCores != null) {
            String[] lines = nodeCPUCores.split("\n");
            boolean hasCores = false;
            boolean hasRAM = false;
            for (String line : lines) {
                if (!hasCores && line.indexOf("cpu:") >= 0) {
                    String[] parts = line.split(":");
                    // number of cores from "Capabity.cpu"
                    int cores = Integer.parseInt(parts[1].trim());
                    log.debug("Available CPU cores in node " + nodeName + ": " + cores);
                    resources[0] = cores;
                    hasCores = true;
                }

                if (!hasRAM && line.indexOf("memory:") >= 0) {
                    String[] parts = line.split(":");
                    // amount of RAM from "Capabity.memory" in Ki
                    int ram = Integer.parseInt(parts[1].replaceAll("[^0-9]", "").trim())/1048576;
                    log.debug("Available memory in node " + nodeName + ": " + ram + " Gi");
                    resources[1] = ram;
                    hasRAM = true;
                }
            }
            
            return resources;
        }
        
        return new int[] {0, 0};
    }
    
    public String getSingleSession(String sessionID) throws Exception {
        Session session = this.getSession(userID, sessionID);
        Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        return gson.toJson(session);
    }
    
    public String listSessions(String typeFilter, String statusFilter, boolean allUsers) throws Exception {
        
        List<Session> sessions = null;
        if (allUsers) {
            sessions = getAllSessions(null);
        } else {
            sessions = getAllSessions(userID);
        }
        
        log.debug("typeFilter=" + typeFilter);
        log.debug("statusFilter=" + statusFilter);
        
        List<Session> filteredSessions = filter(sessions, typeFilter, statusFilter);
        
        // if for all users, only show public information
        String json = null;
        Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        if (allUsers) {
            List<PublicSession> publicSessions = new ArrayList<PublicSession>(filteredSessions.size());
            for (Session s : filteredSessions) {
                publicSessions.add(new PublicSession(s.getUserid(), s.getType(), s.getStatus(), s.getStartTime()));
            }
            json = gson.toJson(publicSessions);
        } else {
            json = gson.toJson(filteredSessions);
        }
        
        return json;
    }
    
    public List<Session> filter(List<Session> sessions, String typeFilter, String statusFilter) {
        List<Session> ret = new ArrayList<Session>();
        for (Session session : sessions) {
            if ((typeFilter == null || session.getType().equalsIgnoreCase(typeFilter)) &&
                (statusFilter == null || session.getStatus().equalsIgnoreCase(statusFilter))) {
                ret.add(session);
            }
        }
        return ret;
    }
    
    public String getEventLogs(String sessionID) throws Exception {
        String events = getEvents(userID, sessionID);
        if (!StringUtil.hasLength(events)) {
            events = "<none>";
        }
        return events + "\n";
    }
    
    public void streamContainerLogs(String sessionID, OutputStream out) throws Exception {
        streamPodLogs(userID, sessionID, out);
    }

}
