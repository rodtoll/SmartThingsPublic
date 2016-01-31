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
            input "isyAddress", "text", title: "ISY Address", required: false, defaultValue: "10.0.1.19"  			// Address of the ISY Hub
            input "isyPort", "number", title: "ISY Port", required: false, defaultValue: 80							// Port to use for the ISY Hub
            input "isyUserName", "text", title: "ISY Username", required: false, defaultValue: "admin"				// Username to use for the ISY Hub
            input "isyPassword", "text", title: "ISY Password", required: false, defaultValue: "password"			// Password to use for the ISY Hub
            input "bridgeAddress", "text", title: "Bridge Address", required: false, defaultValue: "10.0.1.49"		// Address of the bridge
            input "bridgePort", "text", title: "Bridge Port", required: false, defaultValue: 3003					// Port of the bridge
            input "bridgeUserName", "text", title: "Bridge Username", required: false, defaultValue: "admin"		// Username to use with the bridge
            input "bridgePassword", "text", title: "Bridge Password", required: false, defaultValue: "password"	// Password to use for the bridge
    }
}

def installed() {
	setCurrentLoadState("Startup")
    atomicState.rawNodeList = []
    atomicState.deviceDetailsList = [:]
    atomicState.deviceGetDetailsIndex = 0;
	initialize()
}

def updated() {
	log.debug "ISYSMARTAPP: Updated with settings: ${settings}"
}

private getAuthorization() {
    def userpassascii = settings.isyUserName + ":" + settings.isyPassword
    "Basic " + userpassascii.encodeAsBase64().toString()
}

private getMainAddress(sourceAddress) {
	def addressComponents = sourceAddress.split(' ')
    if(addressComponents.size() > 3) {
    	return addressComponents[0]+' '+addressComponents[1]+' '+addressComponents[2]
    } else {
    	return sourceAddress;
    }
}

private getSubAddress(sourceAddress) {
	def addressComponents = sourceAddress.split(' ')
    if(addressComponents.size() > 3) {
    	return addressComponents[3]
    } else {
    	return '1';
    }
}

def findDevice(address) {
    def device = getChildDevice(address)
    if(!device) {
        device = getChildDevice(getMainAddress(incomingAddress))
    }
    return deviceToUpdate
}

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

def handleStatusMessage(xml) {
    log.debug 'ISYSMARTAPP: Processing status response'
    xml.node.each {
        def attributeMap = it.attributes()
        def nodeAddress = attributeMap['id']
        // This is a refresh
        def device = findDevice(nodeAddress) 
        if(device != null) {
            def value = it.property.attributes()['value'].toString()
            if(!value.isInteger()) {
                log.debug "Setting value which is not an int..."+value
                device.setISYState(nodeAddress,value)
            } else {
                log.debug "Setting value whicvh is an int..."+value
                device.setISYState(nodeAddress,0)
            }
        }
    }
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

def findHub() {
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

def getZWaveType(nodeXml) {
	def zWaveSubType = nodeXml?.devtype?.cat?.toString().toInteger()
    if(zWaveSubType != null) {
    	if(zWaveSubType == 111) {
        	return "ZL"
        }
    } else {
    	return "U"
    }
}

// When we get the response from the node info request create the device
def handleNodeInfoMessage(xml) {
    // Parse out device details
    def address = xml.node.address.toString()
    def name = xml.node.name.toString()
    def devType = xml.node.type.toString()
    def enabled = xml.node.enabled.toString().toBoolean()    
    def propNode = xml.node.'property'
    if(propNode.size() == 0) {
    	log.debug "ISYSMARTAPP: Skipping node without property. Not supported"
        enabled = false;
    }
    
    if(enabled) {
        def propAttributes = propNode[0].attributes()
        def stateValue = propAttributes['value'].toString()
        def formattedValue = propAttributes['formatted'].toString()
        def uomValue = propAttributes['uom'].toString()
    
        // Determine root address to use and sub-address for sub-devices
        def rootAddress = getMainAddress(address) 
        def subAddress = getSubAddress(address)

        // Fix up statevalue
        if(stateValue == null) {
            stateValue = " "
        }    

        // Determine isy device type
        def isyType = 'U'
        def isyCollect = false

		def familyNode = xml.node.family
        def familyId = null;
        
        if(familyNode != null) {
        	if(familyNode.toString().isInteger()) {
        		familyId = familyNode.toString().toInteger()
            }
        }
        
        if(familyId == null) {
        	isyType = getIsyType(devType.toString(), address.toString(), uomValue.toString())
        } else if(familyId == 4) {
        	isyType = getZWaveType(xml.node)
        }

        def addressToUse = address

        // Mark devices which we want to treat as composites as such
        if(isyType == 'IO' || isyType == 'IM' || isyType == 'CS') {
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

        if(newDevice != null) {
            if(stateValue == ' ') {
                //log.debug "ISYSMARTAPP: Setting blank default state on device: "+name
                newDevice.setISYState(address, stateValue)
            } else {
                newDevice.setISYState(address, stateValue.toInteger())
            }
        }
    } else {
	    log.debug 'ISYSMARTAPP: Ignoring disabled device. Address='+address
    }

    sendNextGetInfoRequest()
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

def getDeviceNetworkIdForElkZone(zone) {
	return settings.isyAddress+'#Elk#'+zone
}

def handleNodeUpdateMessage(xml) {
    if(xml.control.toString() == 'ST') {
    	def incomingAddress = xml.node.toString()
        def deviceToUpdate = getChildDevice(incomingAddress)
        if(!deviceToUpdate) {
        	deviceToUpdate = getChildDevice(getMainAddress(incomingAddress))
        }
        if(deviceToUpdate) {
            if(xml.action == null || !xml.action.toString().isInteger()) {
            	//log.debug "ISYSMARTAPP: Incoming message had a blank or wrong device state value..."
            	deviceToUpdate.setISYState(incomingAddress, " ")
            } else {
            	deviceToUpdate.setISYState(incomingAddress, xml.action.toString().toInteger())
            }
        } 
    } else if(xml.control.toString() == '_19') {
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
}

private String makeNetworkId(ipaddr, port) { 
     String hexIp = ipaddr.tokenize('.').collect { 
     String.format('%02X', it.toInteger()) 
     }.join() 
     String hexPort = String.format('%04X', port) 
     return "${hexIp}:${hexPort}" 
}

def sendSubscribeCommand() {
    setCurrentLoadState("SendSubscribe")       
    def dni = makeNetworkId(settings.isyAddress,settings.isyPort)
    atomicState.hubDni = dni
	log.debug "ISYSMARTAPP: Now we are sending out subscribe for changes.."+dni
	def newDevice = addChildDevice('rodtoll', 'ISYHub', dni, location.hubs[atomicState.hubIndex].id, [label:"ISY Hub",completedSetup: true])
    newDevice.setParameters(settings.isyAddress,settings.isyPort,settings.isyUserName,settings.bridgeAddress,settings.bridgePort,settings.bridgeUserName,settings.bridgePassword)
    newDevice.subscribe(atomicState.hubIndex)
}
    
def sendNextGetInfoRequest() {
	if(atomicState.rawNodeList == null) {
    	log.debug "ISYSMARTAPP: IGNORING GetNextInfo because we are not yet setup."
        return
    }
    if(atomicState.rawNodeList.size() > 0) {
	    setCurrentLoadState("LoadingDetails"+atomicState.rawNodeList.size())          
    	//log.debug "ISYSMARTAPP: Enumerating devices now... let's do the next one."
    	def nextAddress = atomicState.rawNodeList[0]
        def nodeList = atomicState.rawNodeList
        nodeList.remove(0)
        atomicState.rawNodeList = nodeList
        def requestPath = '/rest/nodes/'+nextAddress
        requestPath = requestPath.replaceAll(" ", "%20")
    	//log.debug "ISYSMARTAPP: Sending request... "+requestPath
        sendHubCommand(getRequest(requestPath))
    } else {
    	atomicState.rawNodeList = null
        setCurrentLoadState('LoadElk')
        sendHubCommand(getRequest('/rest/elk/get/topology'))
    }
}

def setCurrentLoadState(state) {
    atomicState.loadingState = state
    log.debug "ISYSMARTAPP: #### Transitioning to state: "+state
}

def oldHandleElkTopologyMessage(xml) {
    //log.debug 'ISYSMARTAPP: Processing Elk Topology Response'
    def potentialElkNodes = []
    def elkDeviceCount = 0
    xml.areas.area[0].zone.each {
        def elkZoneId = it['@id'].toString()
        def elkZoneName = it['@name'].toString()
        //log.debug 'ISYSMARTAPP: Discovered elk device zone: '+elkZoneId+' name: '+elkZoneName
        potentialElkNodes << elkZoneName + '@' + elkZoneId 
        elkDeviceCount++
    }
    //log.debug 'ISYSMARTAPP: Done list'
    atomicState.rawElkZoneList = potentialElkNodes
    //log.debug 'ISYSMARTAPP: Elk Device list generated. Potential devices: '+atomicState.rawElkZoneList.size()
    setCurrentLoadState('LoadElkStatus')
    sendHubCommand(getRequest('/rest/elk/get/status'))
}

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
    setCurrentLoadState('LoadElkStatus1')
    sendHubCommand(getRequest('/rest/elk/get/status'))
}

/*def handleElkStatusMessage1(xml) {
	log.debug 'ISYSMARTAPP: Processing elk status response - 1st stage'
    
    def rawElkZoneList = atomicState.rawElkZoneList;
    
	xml.ze.each {
    
    	def zoneId = it['@zone'].toString()
        def type = it['@type'].toString()
        def val = it['@val'].toString()
        
        if(type == '53') {
        	if(rawElkZoneList.contains(zoneId)) {
                def voltage = val.toInteger();
                if(voltageEntry < 65 || voltageEntry > 80) {
                	def name = rawElkZone[zoneId]
                    addChildDevice('rodtoll', 'ISYElkAlarmSensor', addressToUse, null, [label:name,completedSetup:true]) 
                }
            }
        }
    }
    
    setCurrentLoadState('LoadElkStatusDetails')
    sendHubCommand(gerRequest('/rest/elk/get/status'))
}*/
    
def handleElkStatusMessage(xml) {
    def rawElkZoneList = atomicState.rawElkZoneList
    def passId = 0
    
    def currentState = atomicState.loadingState
    
    if(currentState == "LoadElkStatus1") {
    	passId = 0
    } else if(currentState == "LoadElkStatus2") {
    	passId = 1
    } else if(currentState == "LoadElkStatus3") {
    	passId = 2
    } else if(currentState == "LoadElkStatus4") {
    	passId = 3
    } else if(currentState == "LoadElkStatus5") {
    	passId = 4
    }
    
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
    
	if(passId == 0) {    
        setCurrentLoadState('LoadElkStatus2')
    	sendHubCommand(getRequest('/rest/elk/get/status'))
    } else if(passId == 1) {
        setCurrentLoadState('LoadElkStatus3')
    	sendHubCommand(getRequest('/rest/elk/get/status'))
    } else if(passId == 2) {
        setCurrentLoadState('LoadElkStatus4')
    	sendHubCommand(getRequest('/rest/elk/get/status'))
    } else if(passId == 3) {
        setCurrentLoadState('LoadElkStatus5')
    	sendHubCommand(getRequest('/rest/elk/get/status'))        
	} else {
	    setCurrentLoadState('LoadElkStatusDetails1')
    	sendHubCommand(getRequest('/rest/elk/get/status'))
    }
}

def handleElkStatusMessageForDetails(xml) {
	log.debug 'ISYSMARTAPP: Processing elk status response - details stage'
        
    for(def index = 0; index < xml.ze.size(); index++) {
    	if(atomicState.loadingState == "LoadElkStatusDetails1") {
        	if(index % 3 != 0) {
            	continue
            }
        } else if(atomicState.loadingState == "LoadElkStatusDetails2") {
        	if(index % 3 != 1) {
            	continue
            }
        } else {
        	if(index % 3 != 2) {
            	continue
            }
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
    if(atomicState.loadingState == "LoadElkStatusDetails1") {
        setCurrentLoadState('LoadElkStatusDetails2')
        sendHubCommand(getRequest('/rest/elk/get/status'))    	
    } else if(atomicState.loadingState == "LoadElkStatusDetails2") {
        setCurrentLoadState('LoadElkStatusDetails3')
        sendHubCommand(getRequest('/rest/elk/get/status'))    	
    } else {
//    	setCurrentLoadState('DONE!')
		sendSubscribeCommand()
    }
}

def oldHandleElkStatusMessage(xml) {
    def rawElkList = [:]
    
    def sourceList = atomicState.rawElkZoneList
    
    sourceList.each {
    	def entry = it.split('@')
        def zoneId = entry[1]
        def zoneName = entry[0]
    	def newEntry = ['name': zoneName]
        rawElkList.put(zoneId, newEntry)
    }
    
    xml.ze.each {
    	def zoneId = it['@zone'].toString()
    	def elkAttributes = rawElkList[zoneId]
        def type = it['@type'].toString()
        def value = it['@val'].toString()
        elkAttributes[type] = value
        rawElkList[zoneId] = elkAttributes
    }
    
    //log.debug 'ISYSMARTAPP: Processing the final elk list'
    //log.debug 'ISYSMARTAPP: List: '+rawElkList
    
    //log.debug 'ISYSMARTAPP: List: '+rawElkList['1']
    
    
    rawElkList.each {
    	//log.debug 'ISYSMARTAPP: Processing zone: '+it.key
        def voltageEntry = it.value['53'].toInteger()
        //log.debug 'ISYSMARTAPP: Voltage of node: '+voltageEntry
        if(voltageEntry < 65 || voltageEntry > 80) {
        	//log.debug 'ISYSMARTAPP: Adding new elk zone: '+it.value['name']
            def addressToUse = getDeviceNetworkIdForElkZone(it.key)
            //log.debug 'ISYSMARTAPP: Adding elk zone with dni: '+addressToUse
            def device = getChildDevice(addressToUse);
            if(device == null) {
            	newDevice = addChildDevice('rodtoll', 'ISYElkAlarmSensor', addressToUse, null, [label:it.value['name'],completedSetup:true]) 
           	} else {
                newDevice.setFromZoneUpdate(51,it.value['51'].toInteger())
                newDevice.setFromZoneUpdate(52,it.value['52'].toInteger())
                newDevice.setFromZoneUpdate(53,it.value['53'].toInteger())
            }
        } else {
        	//log.debug 'ISYSMARTAPP: Ignoring node, voltage Is wrong'
        }
    }
    
	setCurrentLoadState("LoadElkStatus2")

	sendSubscribeCommand()
}

def handleXmlMessage(xml) {
	//log.debug "ISYSMARTAPP: Processing xml message"
    //try {
        if(xml.name() == 'nodes') {
        	if(atomicState.loadingState == "InitialStatusRequest") {
                handleInitialStatusMessage(xml)
                setCurrentLoadState("LoadingNodeDetails")
                sendNextGetInfoRequest()
            } else {
            	handleStatusMessage(xml)
            }
        } else if(xml.name() == 'nodeInfo') {
            handleNodeInfoMessage(xml)
        } else if(xml.name() == 'topology') {
        	handleElkTopologyMessage(xml)
        } else if(xml.name() == 'status') {
        	if(atomicState.loadingState.startsWith('LoadElkStatusDetails')) {
            	handleElkStatusMessageForDetails(xml)
            } else {
        		handleElkStatusMessage(xml)        
            }
        } else if(xml.name() == 'Envelope') {
            setCurrentLoadState("LoadCompleted")
            def hubDevice = getChildDevice(atomicState.hubDni)
            log.debug 'ISYSMARTAPP: Hub device '+hubDevice
            log.debug 'ISYSMARTAPP: XML: '+xml.toString()
            hubDevice.setSubscribIdFromResponse(xml)
            //log.debug 'ISYSMARTAPP: Got an envelope message'
            //log.debug 'ISYSMARTAPP: Envelope '+xml.toString()
        } else if(xml.name() == 'Event') {
            //log.debug 'ISYSMARTAPP: Got a node update message'       
        	handleNodeUpdateMessage(xml)
        } else {
        	log.debug 'ISYSMARTAPP: Ignoring malformed message. Message='+xml.name()
        }
    //} catch(e) {
      //  log.debug 'Error parsing devices: '+e
    //}
}

def locationHandler(evt) {
    //try {
        def msg = parseLanMessage(evt.description)
        if(!msg.xml) {
        	if(msg.body && msg.body.length() > 0) {
	            msg.xml = new XmlSlurper().parseText(msg.body)
            }
        } 
        if(msg.xml) {
        	//log.debug 'Received command... ' + msg.xml.name()
        	handleXmlMessage(msg.xml)
        } else {
            //log.debug 'ISYSMARTAPP: Received non-xml command: '+ msg
        }
    //} catch(e) {
    //    log.debug 'ERROR -- Details: '+ e
   // }
}

def initialize() {
	setCurrentLoadState("Setup")
	findHub()
	subscribe(location, null, locationHandler, [filterEvents:false])
	setCurrentLoadState("InitialStatusRequest")
    sendHubCommand(getRequest('/rest/status'))
}

def doRefresh() {
    sendHubCommand(getRequest('/rest/status'))
}

def getRequest(path) {
	//log.debug "Building request..."+path+" host: "+settings.isyAddress+":"+settings.isyPort+" auth="+getAuthorization()
    new physicalgraph.device.HubAction(
        'method': 'GET',
        'path': path,
        'headers': [
            'HOST': settings.isyAddress+":"+settings.isyPort,
            'Authorization': getAuthorization()
        ], null)
}

def getDeviceAddressSafeForUrl(device) {
	return getStringSafeForUrl(device.device.deviceNetworkId)
}

def getStringSafeForUrl(value) {
	return value.toString().replaceAll(' ', '%20')
}

def on(device,address) {
    def command = '/rest/nodes/'+getStringSafeForUrl(address)+'/cmd/DON'
	sendHubCommand(getRequest(command))
}

def off(device,address) {
    def command = '/rest/nodes/'+getStringSafeForUrl(address)+'/cmd/DOF'
	sendHubCommand(getRequest(command))
}

def setDim(device, level,address) {
	def isyLevel = scaleSTRangeToISYDeviceLevel(level)
    def command = '/rest/nodes/'+getStringSafeForUrl(address)+'/cmd/DON/'+isyLevel
	sendHubCommand(getRequest(command))
}

def setDimNoTranslation(device, isyLevel,address) {
    def command = '/rest/nodes/'+getStringSafeForUrl(address)+'/cmd/DON/'+isyLevel
	sendHubCommand(getRequest(command))
}

def secureLockCommand(device, address, locked) {
    def command = '/rest/nodes/'+getStringSafeForUrl(address)+'/cmd/SECMD/'
    if(locked) {
    	command = command + "1"
    } else {
    	command = command + "0"
    }
	sendHubCommand(getRequest(command))
}

def uninstalled() {
	def currentState = atomicState.loadingState
	log.debug "ISYSMARTAPP: Uninstalling. In current state: "+currentState
	if(atomicState.hubDni != null && currentState == "LoadCompleted") {
    	log.debug "ISYSMARTAPP: Doing an unsubscribe"
    	def hubDevice = getChildDevice(atomicState.hubDni);
        hubDevice.unsubscribe()
    } else {
    	log.debug "ISYSMARTAPP: Not unsubscribing, not needed."
    }
}