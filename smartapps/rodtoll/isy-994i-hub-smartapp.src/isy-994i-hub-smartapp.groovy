/**
 *  SmartApp for Intergrating ISY-994i Hub into SmartThings. 
 *
 *  Copyright 2016 Rod Toll
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
definition(
    name: "ISY 994i Hub SmartApp",
    namespace: "rodtoll",
    author: "Rod Toll",
    description: "Enables ISY 994i Hub to be controlled from SmartThings. Includes notifications so changes to devices are reflected in SmartThings immediately",
    category: "My Apps",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png")

preferences {
	section("ISY Configuration") {
            input "isyAddress", "text", title: "ISY Address", required: false, defaultValue: "10.0.1.44"  			// Address of the ISY Hub
            input "isyPort", "number", title: "ISY Port", required: false, defaultValue: 3000							// Port to use for the ISY Hub
            input "isyUserName", "text", title: "ISY Username", required: false, defaultValue: "admin"				// Username to use for the ISY Hub
            input "isyPassword", "text", title: "ISY Password", required: false, defaultValue: "password"			// Password to use for the ISY Hub
            input "bridgeAddress", "text", title: "Bridge Address", required: false, defaultValue: "10.0.1.44"		// Address of the bridge
            input "bridgePort", "text", title: "Bridge Port", required: false, defaultValue: 3000					// Port of the bridge
    }
}

// USAGE
// 
// You need to load this SmartApp and all the corresponding device types. You then need to run an instance of the st-isy-notify-bridge project which you
// can find here: https://github.com/rodtoll/st-isy-notify-bridge. This acts as a bridge between the ISY and the SmartThings hubs, fixing up web callback
// notifications so they will be SmartThings compatible. At the moment SmartThings rejects messages directly from the ISY. (I've put in a request for a fix
// but nothing yet). Make sure you configure it with your ISY address and port.
//
// IMPLEMENTATION NOTES
//
// This smartapp runs into a lot of SmartThings limitations. Specifically it runs into the maximum message size for REST responses (~32k) and maximum
// exeuction time (20s). As a result, it splits a number of operations into multiple steps to keep from triggering these limits. On smaller setups
// it might be possible to reduce steps but I wanted it to remain flexible. 
//
// The smartapp runs through a lot of states to load all of the data. Here is the loading sequence:
// 1. Call into ISY and get the current status of nodes using /rest/status. This is used because the /rest/nodes message can exceed 32k.
//    The nodes are enumerated and used to build a list of potential ISY devices. The results are put into the atomicState.rawNodeList
//    STATES: InitialStatusRequest => LoadingNodeDetails
// 2. The smartapp then walks through the list of raw nodes and sends a query to ISY to get details on the node. Uses the /rest/node/<address>
//    command. The response is then used to detect the type of device, create it and then set the current state. Note that there are some device
//    types which are represented by multiple nodes. For these nodes they are ignored as the primary device will handle the changes. Device addresses
//    (dnis) will be either the address (for non-composite) or the main address (for composite). 
//    STATES: LoadingDetails(X) => LoadingDetails(X-1)
// 3. When the entire list of devices has been run through then the smart app requests the topology of the elk network using /rest/elk/get/topology.
//    STATES: LoadElk
// 4. The smartapp will then call status multiple times to look through the results and find elk devices which are present and create corresponding
//    devices. It will do this 3 times, each time processing a third of the nodes. When the list is done it goes into the next state. The messages
//    used are /rest/elk/status
//    STATES: LoadElkStatus0 => LoadElkStatus1 => LoadElkStatus2 
// 5. The smartapp will then call status multiple times to look through the results and set the current state of the elk devices. It will do this 5 
//    times. Each time it will process a fifth of the nodes. When the list is done it goes into the next state.
//    STATES: LoadElkStatusDetails:0 => LoadElkStatusDetails:1 => LoadElkStatusDetails:2 => LoadElkStatusDetails:3 => LoadElkStatusDetails:4
// 6. The smartapp will then finish by subscribing the change notifications from the ISY.
//    STATES: SendSubscribe => LoadCompleted

///////////////////////////////////////////////////////////
// Startup and Shutdown
//

// Called when the smart app is installed
def installed() {
	setCurrentLoadState("Startup")
    atomicState.rawNodeList = []
    atomicState.deviceDetailsList = [:]
    atomicState.deviceGetDetailsIndex = 0;
	initialize()
}

// Called when settings are updated. This is always called twice during startup for some reason
def updated() {
	log.debug "ISYSMARTAPP: Updated with settings: ${settings}"
}

// Initializes the SmartApp
def initialize() {
	setCurrentLoadState("Setup")
	findPhysicalHub()
	subscribe(location, null, locationHandler, [filterEvents:false])
	setCurrentLoadState("InitialStatusRequest")
    sendStatusRequest()
}

def uninstalled() {
	def currentState = getCurrentLoadState()
	log.debug "ISYSMARTAPP: Uninstalling. In current state: "+currentState
	if(atomicState.hubDni != null && currentState == "LoadCompleted") {
		sendUnSubscribeCommand()
    } else {
    	log.debug "ISYSMARTAPP: Not unsubscribing, not needed."
    }
}

///////////////////////////////////////////////////////////
// Device change notification management
//

def sendSubscribeCommand() {
    def dni = makeNetworkId(settings.isyAddress,settings.isyPort)
    atomicState.hubDni = dni
	log.debug "ISYSMARTAPP: Now we are sending out subscribe for changes.."+dni
	def newDevice = addChildDevice('rodtoll', 'ISYHub', dni, location.hubs[atomicState.hubIndex].id, [label:"ISY Hub",completedSetup: true])
    newDevice.setParameters(settings.isyAddress,settings.isyPort,settings.isyUserName,settings.bridgeAddress,settings.bridgePort,settings.isyUserName,settings.isyPassword)
    newDevice.subscribe(atomicState.hubIndex)
}

def sendUnSubscribeCommand() {
    log.debug "ISYSMARTAPP: Doing an unsubscribe"
    def hubDevice = getChildDevice(atomicState.hubDni);
    hubDevice.unsubscribe()
}

///////////////////////////////////////////////////////////
// Smart App state tracking
//

def setCurrentLoadState(state) {
    atomicState.loadingState = state
    log.debug "ISYSMARTAPP: #### Transitioning to state: "+state
}

def getCurrentLoadState() {
	return atomicState.loadingState
}

///////////////////////////////////////////////////////////
// Main Message Handler and state handler
//
def handleXmlMessage(xml) {
	//log.debug "ISYSMARTAPP: Incoming message type: "+xml.name()
	def currentState = getCurrentLoadState()
    if(xml.name() == 'nodes') {
        handleInitialStatusMessage(xml)
        setCurrentLoadState("LoadingNodeDetails")
        sendNextGetInfoRequest()
    } else if(xml.name() == 'nodeInfo') {
        handleNodeInfoMessage(xml)
		if(!sendNextGetInfoRequest()) {
            setCurrentLoadState('LoadElk')
            sendElkTopologyRequest()
        }
    } else if(xml.name() == 'topology') {
        handleElkTopologyMessage(xml)
        setCurrentLoadState('LoadElkStatus:0')
        sendElkStatusRequest()        
    } else if(xml.name() == 'status') {
        if(currentState.startsWith('LoadElkStatusDetails')) {
        	def passId = currentState.split(':')[1].toInteger()
            handleElkStatusMessageForDetails(xml,passId)
            passId++
            if(passId <= 2) {
                setCurrentLoadState('LoadElkStatusDetails:'+passId)
                sendElkStatusRequest()   	
            } else {
                setCurrentLoadState("SendSubscribe")       
                sendSubscribeCommand()
            }
        } else {
        	def passId = currentState.split(':')[1].toInteger()
           	handleElkStatusMessage(xml,passId)
            passId++
            if(passId <= 4) {
				setCurrentLoadState('LoadElkStatus:'+passId)
            } else {
            	setCurrentLoadState('LoadElkStatusDetails:0')
            }
            sendElkStatusRequest()
        }
    } else if(xml.name() == 'Envelope') {
        setCurrentLoadState("LoadCompleted")
        def hubDevice = getChildDevice(atomicState.hubDni)
        log.debug 'ISYSMARTAPP: Hub device '+hubDevice
        log.debug 'ISYSMARTAPP: XML: '+xml.toString()
        hubDevice.setSubscribIdFromResponse(xml)
    } else if(xml.name() == 'Event') {
        handleNodeUpdateMessage(xml)
    } else {
        log.debug 'ISYSMARTAPP: Ignoring malformed message. Message='+xml.name()
    }
}

// Handles incoming messages to the SmartApp.
// Handles fixup to ensure it is in xml format then handles to the parser.
def locationHandler(evt) {
    def msg = parseLanMessage(evt.description)
	//log.debug "ISYSMARTAPP: Incoming message..."+msg
    if(!msg.xml) {
    	// The ISY doesn't always seem to properly specify the content-type to activate automatic xml parsing
        // So if we have content and xml is not present try and manually parse it into xml
        if(msg.body && msg.body.length() > 0) {
            msg.xml = new XmlSlurper().parseText(msg.body)
        }
    } 
    if(msg.xml) {
    	//log.debug "ISYSMARTAPP: Xml message being process"
        handleXmlMessage(msg.xml)
    } 
}

///////////////////////////////////////////////////////////
// ISY General Message Handlers
//

// Handle the first status message
// 
// This is used to determine the list of ISY addresses which may be devices. 
//
// Handles result of sendStatusRequest message
def handleInitialStatusMessage(xml) {
    log.debug 'ISYSMARTAPP: Processing status response'

    def sourceNodeList = []
    def deviceCount = 0
    xml.node.each {
        def attributeMap = it.attributes()
        def nodeAddress = attributeMap['id']
        if(!atomicState.rawNodeList.contains(nodeAddress) /*&& sourceNodeList.size() < 5*/) {
            def propValue = attributeMap['value']
            sourceNodeList << nodeAddress
            //log.debug 'ISYSMARTAPP: Discovered node of address: '+nodeAddress
            deviceCount++
        }
    }
    atomicState.rawNodeList = sourceNodeList
    log.debug 'ISYSMARTAPP: Device address list generated. Potential devices: '+atomicState.rawNodeList.size()
}

// Handles creating a device for the device node specified in the xml paramter.
// 
// This message comes from a response from a sendGetNodeInfo() message.
//
def handleNodeInfoMessage(xml) {
    // Parse out device details
    def address = xml.node.address.toString()
    def name = xml.node.name.toString()
    def devType = xml.node.type.toString()
    def enabled = xml.node.enabled.toString().toBoolean()    
    def propNode = xml.node.'property'
  
  	// This node has no value. Not supported in this SmartApp
    if(propNode.size() == 0) {
    	log.debug "ISYSMARTAPP: Skipping node without property. Not supported"
        enabled = false;
    }
    
    // Only include devices which are enabled
    if(enabled) {
    	// Get current device state
        def propAttributes = propNode[0].attributes()
        def stateValue = propAttributes['value'].toString()
        def formattedValue = propAttributes['formatted'].toString()
        def uomValue = propAttributes['uom'].toString()
    
        // Determine root address to use and sub-address for sub-devices
        def rootAddress = getIsyMainAddress(address) 
        def subAddress = getIsySubAddress(address)

        // Determine isy device type
        def isyType = 'U'
        def isyCollect = false

		// Determine device family
        def familyId = getFamilyIdFromDevice(xml.node)
        
        // Normalize missing state values
        if(stateValue == null || stateValue == "" || stateValue == " ") {
        	stateValue = " "
        }
        
        // Determine the device type
        if(familyId == 1) {
        	isyType = getIsyType(devType, address, uomValue)
        } else if(familyId == 4) {
        	def zWaveSubType = xml.node.devtype.cat.toString().toInteger()
        	isyType = getZWaveType(zWaveSubType)
        }

        def addressToUse = address

        // Mark devices which we want to treat as composites as such
        if(isCompoundIsyDevice(isyType)) {
            isyCollect = true
            addressToUse = rootAddress
        }

        log.debug 'ISYSMARTAPP: DEVICE: isyType='+isyType+' addr='+address+' rootAddress='+rootAddress+' subAddr='+subAddress+' name='+name+' isyType='+isyType+' isyCollect='+isyCollect+' devType='+devType+' state='+stateValue+' form='+formattedValue+' uom='+uomValue

        def newDevice = null

        if(isyCollect && getChildDevice(addressToUse) != null) {
            newDevice = getChildDevice(addressToUse)
        } else {
            if(isyType == "DL" ) {
                //log.debug "ISYSMARTAPP: Creating Dimmable device"
                newDevice = addChildDevice('rodtoll', 'ISYDimmableLight', addressToUse, null, [label:name,completedSetup: true])
            } else if(isyType == "L") {
                //log.debug "ISYSMARTAPP: Creating On Off Light device"
                newDevice = addChildDevice('rodtoll', 'ISYOnOffLight', addressToUse, null, [label:name,completedSetup:true])
            } else if(isyType == "F") {
                //log.debug "ISYSMARTAPP: Creating FAN device"
                newDevice = addChildDevice('rodtoll', 'ISYFanLinc', addressToUse, null, [label:name,completedSetup:true])
            } else if(isyType == "O") {
                //log.debug "ISYSMARTAPP: Creating Outlet device"
                newDevice = addChildDevice('rodtoll', 'ISYOutlet', addressToUse, null, [label:name,completedSetup:true])  
            } else if(isyType == "CS") {
                //log.debug "ISYSMARTAPP: Creating Contact Sensor device"
                newDevice = addChildDevice('rodtoll', 'ISYContactSensor', addressToUse, null, [label:name,completedSetup:true]) 
            } else if(isyType == "IM") {
                //log.debug "ISYSMARTAPP: Creating Motion Sensor device"
                newDevice = addChildDevice('rodtoll', 'ISYMotionSensor', addressToUse, null, [label:name,completedSetup:true])   
            } else if(isyType == "ML") {
                //log.debug "ISYSMARTAPP: Creating MorningLinc device"
                newDevice = addChildDevice('rodtoll', 'ISYMorningLinc', addressToUse, null, [label:name,completedSetup:true])    
            } else if(isyType == "ZL") {
                //log.debug "ISYSMARTAPP: Creating ZWaveLock device"
                newDevice = addChildDevice('rodtoll', 'ISYZWaveLock', addressToUse, null, [label:name,completedSetup:true])                   
            } else {
                log.debug "ISYSMARTAPP: Ignoring device: dni="+addressToUse+"+ name="+name+" type="+isyType
            }
        }

		// If we have a device set it's initial state 
        if(newDevice != null) {
            if(stateValue == " ") {
                //log.debug "ISYSMARTAPP: Setting blank default state on device: "+name
                newDevice.setISYState(address, stateValue)
            } else {
                newDevice.setISYState(address, stateValue.toInteger())
            }
        }
    } else {
	    log.debug 'ISYSMARTAPP: Ignoring disabled device. Address='+address
    }
}

// Handles an update message from the ISY. These come from web notifications
def handleNodeUpdateMessage(xml) {
	// Device state update
    if(xml.control.toString() == 'ST') {
    	handleIsyDeviceUpdate(xml)
    // Elk state update
    } else if(xml.control.toString() == '_19') {
		handleElkZoneUpdate(xml)
    }
}

// HELPER: Gets the family id from the device node entry
def getFamilyIdFromDevice(deviceNode) {	
	def familyNode = deviceNode.family
    if(familyNode != null) {
        if(familyNode.toString().isInteger()) {
            return familyNode.toString().toInteger()
        }
    }
    return 1
}

// HELPER: Sends the message to get the next device if there are any left in the raw list
def sendNextGetInfoRequest() {
	def rawNodeList = atomicState.rawNodeList
	if( rawNodeList == null) {
    	log.debug "ISYSMARTAPP: IGNORING GetNextInfo because we are not yet setup."
        return false
    }
    if(rawNodeList.size() > 0) {
	    setCurrentLoadState("LoadingDetails"+atomicState.rawNodeList.size())          
    	def nextAddress = rawNodeList[0]
        rawNodeList.remove(0)
        atomicState.rawNodeList = rawNodeList
        sendGetNodeInfo(nextAddress)
        return true
    } else {
    	atomicState.rawNodeList = null
        return false
    }
}

// HELPER: Processes a web notification when it is for a non-Elk device
def handleIsyDeviceUpdate(xml) {
    def incomingAddress = xml.node.toString()
    def deviceToUpdate = getChildDevice(incomingAddress)
    if(!deviceToUpdate) {
        deviceToUpdate = getChildDevice(getIsyMainAddress(incomingAddress))
    }
    if(deviceToUpdate) {
        if(xml.action == null || !xml.action.toString().isInteger()) {
            //log.debug "ISYSMARTAPP: Incoming message had a blank or wrong device state value..."
            deviceToUpdate.setISYState(incomingAddress, " ")
        } else {
            deviceToUpdate.setISYState(incomingAddress, xml.action.toString().toInteger())
        }
    } 
}

///////////////////////////////////////////////////////////
// Elk General Message Handlers
//

def handleElkTopologyMessage(xml) {
    log.debug 'ISYSMARTAPP: Processing Elk Topology Response'
    def rawElkNodeList = [:]
    def elkDeviceCount = 0
    xml.areas.area[0].zone.each {
        def elkZoneId = it['@id'].toString()
        def elkZoneName = it['@name'].toString()
        def elkAlarmDef = it['@alarmDef'].toString()
        rawElkNodeList.put(elkZoneId, elkZoneName+"@"+elkAlarmDef)
        elkDeviceCount++
    }
    atomicState.rawElkZoneList = rawElkNodeList
}
    
def handleElkStatusMessage(xml, passId) {
    def rawElkZoneList = atomicState.rawElkZoneList
	log.debug 'ISYSMARTAPP: Processing elk status response - Pass: '+passId
    for(def index = 0; index < xml.ze.size(); index++) {
    	if(index % 5 != passId) {
        	continue;
        } 
    	def zeEntry = xml.ze[index]
    	def zoneId = zeEntry['@zone'].toString()
        def type = zeEntry['@type'].toString()
        def val = zeEntry['@val'].toString()
        if(type == '53') {
        	def zoneParts = rawElkZoneList[zoneId].split('@')
            def zoneName = zoneParts[0]
            def zoneAlarmDef = zoneParts[1].toInteger()
        	if(zoneName != null) {
                def voltage = val.toInteger();
                if(voltage < 65 || voltage > 80) {
            		def addressToUse = getDeviceNetworkIdForElkZone(zoneId)
                    //log.debug 'ISYSMARTAPP: Adding device: '+addressToUse
                    if(zoneAlarmDef == 17) {
	                    addChildDevice('rodtoll', 'ISYElkCOSensor', addressToUse, null, [label:zoneName,completedSetup:true]) 
					} else if(zoneAlarmDef != 15) {
	                    addChildDevice('rodtoll', 'ISYElkAlarmSensor', addressToUse, null, [label:zoneName,completedSetup:true]) 
                    }
				}
            }
        }
    }
}

def handleElkStatusMessageForDetails(xml,passId) {
	log.debug 'ISYSMARTAPP: Processing elk status response - details stage - pass:'+passId
    def currentLoadingState = getCurrentLoadState()

    for(def index = 0; index < xml.ze.size(); index++) {
    	if(index % 3 != passId) {
        	continue
        }
        def zeEntry = xml.ze[index]
    	def zoneId = zeEntry['@zone'].toString()
        def type = zeEntry['@type'].toString().toInteger()
        def val = zeEntry['@val'].toString().toInteger()
        
		def addressToUse = getDeviceNetworkIdForElkZone(zoneId)
        def deviceToUpdate = getChildDevice(addressToUse)
        
        if(deviceToUpdate != null && type == 51) {
            deviceToUpdate.setZone(zoneId)
            deviceToUpdate.setFromZoneUpdate(type,val)
        }
    }
}

// HELPER: Processes a web notification when it is for an Elk device
def handleElkZoneUpdate(xml) {
    if(xml.action != null && xml.action.toString().isInteger()) {
        def actionType = xml.action.toString().toInteger()
        if(actionType == 3) {
            def zeNode = xml.eventInfo.ze
            def zoneId = zeNode['@zone'].toInteger()
            def type = zeNode['@type'].toInteger()
            def value = zeNode['@val'].toInteger()
            def elkDni = getDeviceNetworkIdForElkZone(zoneId)
            def elkDevice = getChildDevice(elkDni)
            if(elkDevice != null) {
                elkDevice.setFromZoneUpdate(type,value)
            } else {
                log.debug "ISYSMARTAPP: Could not find specified elk device"
            }
        } else {
            log.debug "ISYSMARTAPP: Ignoring actionType "+actionType+" message"
        }
    } else {
        log.debug "ISYSMARTAPP: Incoming update doesn't have an action element, skipping"
    }
}

///////////////////////////////////////////////////////////
// Generic Helper functions
//

private String makeNetworkId(ipaddr, port) { 
     String hexIp = ipaddr.tokenize('.').collect { 
     String.format('%02X', it.toInteger()) 
     }.join() 
     String hexPort = String.format('%04X', port) 
     return "${hexIp}:${hexPort}" 
}

def findPhysicalHub() {
    def savedIndex = 0
	for (def i = 0; i < location.hubs.size(); i++) {
        def hub = location.hubs[i]
        if(hub.type.toString() == "PHYSICAL") {
        	savedIndex = i
        }
    }
    log.debug "ISYSMARTAPP: Picking hub: "+savedIndex
    atomicState.hubIndex = savedIndex
}


///////////////////////////////////////////////////////////
// REST Request Basics
//

def getDeviceAddressSafeForUrl(device) {
	return getStringSafeForUrl(device.device.deviceNetworkId)
}

def getStringSafeForUrl(value) {
	return value.toString().replaceAll(' ', '%20')
}

private getAuthorization(userName, password) {
    def userpassascii = userName + ":" + password
    return "Basic " + userpassascii.encodeAsBase64().toString()
}

def getRestGetRequest(path) {
	//log.debug "Building request..."+path+" host: "+settings.isyAddress+":"+settings.isyPort+" auth="+getAuthorization(settings.isyUserName, settings.isyPassword)
    new physicalgraph.device.HubAction(
        'method': 'GET',
        'path': path,
        'headers': [
            'HOST': settings.isyAddress+":"+settings.isyPort,
            'Authorization': getAuthorization(settings.isyUserName, settings.isyPassword)
        ], null)
}

///////////////////////////////////////////////////////////
// Elk Helper functions
//

def getDeviceNetworkIdForElkZone(zone) {
	return settings.isyAddress+'#Elk#'+zone
}

///////////////////////////////////////////////////////////
// ISY Helper functions
//

private getIsyMainAddress(sourceAddress) {
	def addressComponents = sourceAddress.split(' ')
    if(addressComponents.size() > 3) {
    	return addressComponents[0]+' '+addressComponents[1]+' '+addressComponents[2]
    } else {
    	return sourceAddress;
    }
}

private getIsySubAddress(sourceAddress) {
	def addressComponents = sourceAddress.split(' ')
    if(addressComponents.size() > 3) {
    	return addressComponents[3]
    } else {
    	return '1';
    }
}

def scaleISYDeviceLevelToSTRange(incomingLevel) {
    def topOfLevel = 99 * incomingLevel
    def bottomOfLevel = 255
    def scaledLevel = topOfLevel / bottomOfLevel
    def scaled2Level = (Integer) (Math.ceil(topOfLevel / bottomOfLevel))
    scaled2Level
}

def scaleSTRangeToISYDeviceLevel(incomingLevel) {
	def topOfLevel = 255 * incomingLevel
    def bottomOfLevel = 99
    def scaledLevel = topOfLevel / bottomOfLevel
    def scaled2Level = (Integer) (Math.ceil(topOfLevel / bottomOfLevel))
	scaled2Level
}

def getIsyType(devType, address, uom) {

	if(address == null || address.split(' ').size() < 4) {
        log.debug "ISYSMARTAPP: Invalid address, exiting early"
    	return "Unknown"
    }
        
	def devTypePieces = devType.split('\\.')
    def mainType = devTypePieces[0].toInteger()
    def subType = devTypePieces[1].toInteger()
    def subAddress = address.split(' ')[3].toInteger()
    
    //log.debug 'ISYSMARTAPP: Determining type... main='+mainType+' subType='+subType+' subAddress='+subAddress
    
    // Dimmable Devices
    if(mainType == 1) {
    	if(subType == 46 && subAddress == 2) {
        	return 'F'
        } else {
        	return 'DL'
        }
    // Non-Dimmable
    } else if(mainType == 2) {
    	// ApplianceLinc
    	if(subType == 6 || subType == 9 || subType == 12 || subType == 23) {
        	return "O"
        // Outlet Linc
        } else if(subType == 8 || subType == 33) {
        	return "O"
        // Dual Outlets
		} else if(subType == 57) {
        	return "O"
        } else {
        	return "L"
        }
    // Sensors
    } else if(mainType == 7) {
    	// I/O Lincs
    	if(subType == 0) {
        	// Sensor
            return "IO"
        } else {
        	return "U"
        }
    // Door Locks
    } else if(mainType == 15) {
        // MorningLinc positive to lock device
    	if(subType == 6 && subAddress == 1) {
        	return "ML"
        } else {
        	return "U"
        }
    // More sensors...
    } else if(mainType == 16) {
    	// Motion sensors
    	if(subType == 1 || subType == 3) {
        	return "IM"
        } else if(subType == 2 || subType == 9 || subType == 17) {
        	return "CS"
        }
        return "U"
    } else {
    	return "U"
    }
}

def getZWaveType(zWaveSubType) {
    if(zWaveSubType != null) {
    	if(zWaveSubType == 111) {
        	return "ZL"
        }
    } else {
    	return "U"
    }
}

def isCompoundIsyDevice(isyType) {
	return (isyType == 'IO' || isyType == 'IM' || isyType == 'CS')	
}

///////////////////////////////////////////////////////////
// Command Execution (SmartThings->ISY)
//
// Insteon Devices

def on(device,address) {
    def command = '/rest/nodes/'+getStringSafeForUrl(address)+'/cmd/DON'
	sendHubCommand(getRestGetRequest(command))
}

def off(device,address) {
    def command = '/rest/nodes/'+getStringSafeForUrl(address)+'/cmd/DOF'
	sendHubCommand(getRestGetRequest(command))
}

def setDim(device, level,address) {
	def isyLevel = scaleSTRangeToISYDeviceLevel(level)
    def command = '/rest/nodes/'+getStringSafeForUrl(address)+'/cmd/DON/'+isyLevel
	sendHubCommand(getRestGetRequest(command))
}

def setDimNoTranslation(device, isyLevel,address) {
    def command = '/rest/nodes/'+getStringSafeForUrl(address)+'/cmd/DON/'+isyLevel
	sendHubCommand(getRestGetRequest(command))
}

def sendGetNodeInfo(address) {
	def command = '/rest/nodes/'+getStringSafeForUrl(address)
    sendHubCommand(getRestGetRequest(command))
}

///////////////////////////////////////////////////////////
// Command Execution (SmartThings->ISY)
//
// ZWave Devices

def secureLockCommand(device, address, locked) {
    def command = '/rest/nodes/'+getStringSafeForUrl(address)+'/cmd/SECMD/'
    if(locked) {
    	command = command + "1"
    } else {
    	command = command + "0"
    }
	sendHubCommand(getRestGetRequest(command))
}

def sendStatusRequest() {
    sendHubCommand(getRestGetRequest('/rest/status'))
}

///////////////////////////////////////////////////////////
// Command Execution (SmartThings->ISY)
//
// Elk Devices

def sendElkStatusRequest() {
	sendHubCommand(getRestGetRequest('/rest/elk/get/status'))    	
}

def sendElkTopologyRequest() {
	sendHubCommand(getRestGetRequest('/rest/elk/get/topology'))    	
}