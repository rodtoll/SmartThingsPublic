/**
 *  ISYElkCOSensor - Represents a single elk sensor connected to an ISY.
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
  *
 */

metadata {
    definition (name: "ISYElkCOSensor", namespace: "rodtoll", author: "Rod Toll") {
        capability "Carbon Monoxide Detector "
        capability "Actuator"
        attribute "elkZone", "string"
        attribute "logicalState", "string"
    }

    simulator {
    }
    
	tiles(scale: 2) {
        multiAttributeTile(name:"carbonMonoxide", type: "generic", width: 6, height: 4){
            tileAttribute ("device.carbonMonoxide", key: "PRIMARY_CONTROL") {
                attributeState "detected", label:'${name}', icon:"st.alarm.carbon-monoxide.carbon-monoxide.detected", backgroundColor:"#ffa81e"
                attributeState "clear", label:'${name}', icon:"st.alarm.carbon-monoxide.carbon-monoxide.clear", backgroundColor:"#79b821"
            }
            tileAttribute ("device.elkZone", key: "SECONDARY_CONTROL") {
            	attributeState "default", label: 'Zone: ${currentValue}'
            }              
        }
        
        valueTile("logicalState", "device.logicalState", decoration: "Flat", inactiveLabel: false, width: 2, height: 2) {
        	state "default", label: '${currentValue}', defaultState: true
        }   
		
		main "carbonMonoxide"
		details(["carbonMonoxide", "logicalState"])
	}    
}

def setZone(zoneId) {
	sendEvent(name: 'elkZone', value: zoneId)
}

// Does nothing as the smartapp receives the status messages for the device and updates the devices.
def parse(String description) {
}

def setLogicalState(value) {
	//log.debug 'ELKALARMSENSOR: Setting logical state: '+value
	if(value == 0) {
    	sendEvent(name: 'logicalState', value:'Normal')
        //sendEvent(name: 'bypassed', value: 'Not Bypassed')
        sendEvent(name: 'carbonMonoxide', value: 'clear')
    } else if(value == 1) {
    	sendEvent(name: 'logicalState', value:'Trouble')
        //sendEvent(name: 'bypassed', value: 'Not Bypassed')
        sendEvent(name: 'carbonMonoxide', value: 'detected')
    } else if(value == 2) {
    	sendEvent(name: 'logicalState', value:'Violated')
        //sendEvent(name: 'bypassed', value: 'Not Bypassed')
        sendEvent(name: 'carbonMonoxide', value: 'detected')
    } else if(value == 3) {
    	sendEvent(name: 'logicalState', value:'Bypassed')
        //sendEvent(name: 'bypassed', value: 'Bypassed')
        sendEvent(name: 'carbonMonoxide', value: 'clear')
    }
}

def setFromZoneUpdate(type,value) {
	//log.debug 'ELKALARMSENSOR: Setting from zone update '+type+'='+value

	if(type == 51) {
    	setLogicalState(value)
    } else if(type == 52) {
    	//setPhysicalState(value)
    } else if(type == 53) {
    	//sendEvent(name:'voltage', value: value)
    }
}