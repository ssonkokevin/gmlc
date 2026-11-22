# Open questions for the network operator

Questions whose answers change what this gateway sends on the wire, or what the
console may claim. Until an answer is recorded here, the code keeps its
fail-closed default and no document may state the answer as fact. "Do not
assume the answer" applies to every item below.

Owner for answers: the network planning contact at IPLOOK (MME/HSS/MSC/SGSN
team), recorded by date and name. A verbal answer is not enough for a change;
it has to be written down here or in a signed interface description.

## Q1. Which SLh and SLg profile does the MME and HSS expect from an external GMLC?

This gateway is an external GMLC that will talk to IPLOOK's MME (SLg) and HSS
(SLh) over Diameter. The 3GPP application identifiers are 16777255 for SLg
and 16777291 for SLh. The open points are:

1. Does the MME/HSS accept those two Auth-Application-Id values as plain AVPs
   in the Capabilities-Exchange-Request (CER), or does its Diameter stack
   require them inside a Vendor-Specific-Application-Id grouped AVP with
   Vendor-Id 10415?
2. Does the peer validate Supported-Vendor-Id, and does it expect the GMLC to
   list the 3GPP vendor id?
3. Which SLg operations are enabled: PLR/PLA only, or also LRR/LRA if a
   location report is requested? Which SLh operations are enabled: RIR/RIA
   only, or also LCS privacy checks pushed by the GMLC?
4. Does the peer require a particular Destination-Realm, or does it accept the
   realm this gateway sends in its Origin-Realm?
5. Is there a vendor-specific AVP or a dictionary profile that the MME expects
   an external GMLC to load, and where is it documented?

What the code does today, until answered: it advertises 16777291 and 16777255
as plain Auth-Application-Id AVPs in the CER and uses the standard 3GPP SLh/SLg
messages. It does not wrap them in Vendor-Specific-Application-Id and does not
send Supported-Vendor-Id. The transport is single-exchange and has no
concurrent in-flight request handling yet (see `docs/location/PERFORMANCE.md`).

## Q2. Which Lh and Lg profile does the MSC, SGSN and HLR expect?

For 2G/3G the gateway uses MAP over SCCP:

1. Does the MSC/SGSN accept a Provide-Subscriber-Location (PSL) request from an
   external GMLC, or does IPLOOK only support the gateway sending
   Send-Routing-Info-for-LCS (SRILCS) and then asking the serving node?
2. Does the MSC/SGSN initiate SLR (network-initiated location) toward an
   external GMLC, and if so what SCCP address and subsystem number does it
   use to reach the gateway? (Do not send the actual point code or global
   title in this document; record only that the operator supplied it out of
   band and that it was loaded from deployment settings.)
3. Does the HLR answer SRILCS for a roaming or an EPS subscriber, or must the
   gateway use ATI instead?
4. Is Lh privacy checking (Send-LCS-Location / LCS privacy profile) performed
   at the HLR, the MSC/SGSN, or not at all?
5. Does the HLR return a subscriber's LCS privacy profile that the gateway is
   expected to honour, or does it expect the gateway to hold consent itself?

What the code does today: SRILCS then PSL at the MSC/SGSN, or ATI for an EPS
subscriber. MAP SLR (network-initiated service response) is not implemented.
Privacy and consent are held by this gateway; the HLR's own LCS privacy profile
is not read.

## Q3. How does an external GMLC reach the Diameter and MAP peers?

1. Does the external GMLC connect directly to the MME/HSS/STP, or through a
   Diameter Routing Agent and an STP? If through a DRA, which realm and route
   does it publish for the GMLC?
2. Is the connection over a private link, a VPN, or IPsec? Which addresses and
   ports are opened for the GMLC, and are those fixed or negotiated per site?
3. Does the operator require a specific Origin-Host name form or GMLC number
   for the external GMLC?

What the code does today: peer host, port and realm come from deployment
settings only. No route, realm or point-code value is committed to this
repository, and the console holds no route editor.

## Q4. What does IPLOOK return for a cell that is not in its plan?

1. When the MME returns a cell identity the gateway's cell plan does not
   contain, should the gateway fail closed (as now), or does the operator want
   a separate "cell not in plan" alert routed somewhere?
2. Does the MME return the E-UTRAN Cell Global Identity in the same form the
   plan uses (MCC/MNC/TAC/ECI), or with a vendor-specific padding that needs a
   documented transform?

## Q5. Assisted and network-based methods

1. Is any LPPa interface enabled on the operator's eNBs? Without it this
   gateway cannot use E-CID or OTDOA, and the capability profile keeps them
   at NOT_AVAILABLE.
2. Does the operator run a SUPL server or SLP? This gateway does not, and the
   capability profile states `supl.implemented: false`. If the operator runs
   one, confirm it is a separate service the gateway does not call.

## Q6. Numbering used in the console

The console labels the 5G location service interfaces beside their 3GPP
service names. The labels follow the roadmap's shorthand (NL1 as AMF-LMF,
NL2 as Namf_Location, NL5 as NEF, NL6 as Nudm). Confirm the mapping the
operator and the core team want to see, since TS 23.273 numbers NL1 as the
UE-AMF reference point and NL2 as AMF-LMF. The console currently shows NL1 as
"AMF - LMF" as requested; a correction is a label change, not a code change to
any protocol.

## What stays fixed until these are answered

- No Diameter application is wrapped in Vendor-Specific-Application-Id, and no
  vendor id is claimed, because that is a guess until Q1 is answered.
- No point code, global title, subsystem number or realm value is committed or
  printed. These come from deployment settings and are supplied out of band.
- Cell-ID remains the only method the gateway answers; the capability profile
  keeps every other method at PLANNED or NOT_AVAILABLE.
- Nothing in this document is a claim that a real network has answered. Until
  an answer is recorded with a date and a name, the console and the API keep
  saying "not configured" rather than "supported".
