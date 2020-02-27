// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.cloud.healthcare.imaging.dicomadapter;

import com.beust.jcommander.JCommander;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.healthcare.DicomWebClient;
import com.google.cloud.healthcare.DicomWebClientJetty;
import com.google.cloud.healthcare.IDicomWebClient;
import com.google.cloud.healthcare.LogUtil;
import com.google.cloud.healthcare.imaging.dicomadapter.cstoresender.CStoreSenderFactory;
import com.google.cloud.healthcare.imaging.dicomadapter.monitoring.Event;
import com.google.cloud.healthcare.imaging.dicomadapter.monitoring.MonitoringService;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import org.dcm4che3.net.Device;
import org.dcm4che3.net.service.BasicCEchoSCP;
import org.dcm4che3.net.service.DicomServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// ShaftesBury
public class ImportAdapter
{
    private static Logger log = LoggerFactory.getLogger(ImportAdapter.class);

    public static void main(String[] args) throws IOException, GeneralSecurityException
    {
        boolean isDebug = true;
        Flags flags = new Flags();
        if (isDebug)
        {
            flags = new Flags(
            "Horos_wimtach",//dimse_aet=IMPORTADAPTER,
            // Desc: Title of DIMSE Application Entity.
            "", // Desc: (Optional) Separate AET used for
            // C-STORE calls within context of C-MOVE.
            2579,// dimse_port=2579, Desc: Port the server
            // is listening to for incoming DIMSE requests.
            "", // Desc: Address for DicomWeb service. Deprecated and used only with C-STORE. If dicomweb_address is also specified, it takes precedence.
            "", // Desc: Path to send StowRS requests for DicomWeb peer. This is appended to the contents of --dicomweb_addr flag. Deprecated and used only with C-STORE. If dicomweb_address is also specified, it takes precedence.
            "https://healthcare.googleapis.com/v1beta1/projects/centennial-dev/locations/northamerica-northeast1/datasets/AxonDataset5/dicomStores/AxonDataStore6/dicomWeb",//dicomweb_address=https://healthcare.googleapis.com/v1beta1/projects/${PROJECT}/locations/${LOCATION}/datasets/${DATASET}/dicomStores/${DICOMSTORE}/dicomWeb, Desc: Address for DicomWeb service. Must be a full path up to /dicomWeb if the Cloud Healthcare API is used.
            "https://www.googleapis.com/auth/cloud-platform",// oauth_scopes=https://www.googleapis.com/auth/cloud-platform, Desc: Comma seperated OAuth scopes used by adapter.
            "/home/eduardosantana/configFiles/env_aets_json.json",// Desc: Path to json containing aet definitions (array containing name/host/port per element)
            "",// Desc: Json array containing aet definitions (name/host/port per element). Only one of aet_dictionary and aet_dictionary_inline needs to be specified.
            "centennial-dev");// monitoring_project_id=${PROJECT}, Desc: Stackdriver monitoring project id, must be the same as the project id in which the adapter is running
        }
        else
        {
            JCommander jCommander = new JCommander(flags);
            jCommander.parse(args);
        }

        // Adjust logging.
        if (flags.verbose)
        {
            LogUtil.Log4jToStdout();
        }

        // Credentials, use the default service credentials.
        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
        if (!flags.oauthScopes.isEmpty())
        {
            credentials = credentials.createScoped(Arrays.asList(flags.oauthScopes.split(",")));
        }

        HttpRequestFactory requestFactory =
                new NetHttpTransport().createRequestFactory(new HttpCredentialsAdapter(credentials));

        // Initialize Monitoring
        if (!flags.monitoringProjectId.isEmpty())
        {
            MonitoringService.initialize(flags.monitoringProjectId, Event.values(), requestFactory);
            MonitoringService.addEvent(Event.STARTED);
        } else
        {
            MonitoringService.disable();
        }

        // Dicom service handlers.
        DicomServiceRegistry serviceRegistry = new DicomServiceRegistry();

        // Handle C-ECHO (all nodes which accept associations must support this).
        serviceRegistry.addDicomService(new BasicCEchoSCP());

        // Handle C-STORE
        String cstoreDicomwebAddr = flags.dicomwebAddress;
        String cstoreDicomwebStowPath = "studies";
        if (cstoreDicomwebAddr.length() == 0)
        {
            cstoreDicomwebAddr = flags.dicomwebAddr;
            cstoreDicomwebStowPath = flags.dicomwebStowPath;
        }

        IDicomWebClient cstoreDicomWebClient =
                new DicomWebClientJetty(credentials, cstoreDicomwebAddr);
        CStoreService cStoreService =
                new CStoreService(cstoreDicomwebStowPath, cstoreDicomWebClient);
        serviceRegistry.addDicomService(cStoreService);

        // Handle C-FIND
        IDicomWebClient dicomWebClient =
                new DicomWebClient(requestFactory, flags.dicomwebAddress);
        CFindService cFindService = new CFindService(dicomWebClient);
        serviceRegistry.addDicomService(cFindService);

        // Handle C-MOVE
        String cstoreSubAet = flags.dimseCmoveAET.equals("") ? flags.dimseAET : flags.dimseCmoveAET;
        CStoreSenderFactory cStoreSenderFactory = new CStoreSenderFactory(cstoreSubAet, dicomWebClient);
        AetDictionary aetDict = new AetDictionary(flags.aetDictionaryInline, flags.aetDictionaryPath);
        CMoveService cMoveService = new CMoveService(dicomWebClient, aetDict, cStoreSenderFactory);
        serviceRegistry.addDicomService(cMoveService);

        // Handle Storage Commitment N-ACTION
        serviceRegistry.addDicomService(new StorageCommitmentService(dicomWebClient, aetDict));

        // Start DICOM server
        Device device = DeviceUtil.createServerDevice(flags.dimseAET, flags.dimsePort, serviceRegistry);
        device.bindConnections();

    }
}
