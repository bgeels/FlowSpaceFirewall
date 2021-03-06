/*
 Copyright 2014 Trustees of Indiana University

   Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package edu.iu.grnoc.flowspace_firewall;


import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.TimerTask;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.floodlightcontroller.core.IOFSwitch;

import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFStatisticsRequest;

import org.openflow.protocol.statistics.OFFlowStatisticsReply;
import org.openflow.protocol.statistics.OFFlowStatisticsRequest;
import org.openflow.protocol.statistics.OFPortStatisticsReply;
import org.openflow.protocol.statistics.OFPortStatisticsRequest;
import org.openflow.protocol.statistics.OFStatistics;
import org.openflow.protocol.statistics.OFStatisticsType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * 
 * @author aragusa
 *
 */

public class FlowStatCacher extends TimerTask{

	private static String cacheFile = "/var/run/fsfw/flowCache.ser";
	FlowStatCache statsCache;
	private static final Logger log = LoggerFactory.getLogger(FlowStatCacher.class);
	
	/**
	 * A TimerTask that everytime is run gets the most recent 
	 * stats from the switch and caches them
	 * @throws  
	 */
	
	public FlowStatCacher(FlowSpaceFirewall parent){
		statsCache = new FlowStatCache(parent);

		
	}
	/**
	 * the TimerTask run method called by the Timer
	 * Loops through the array of Switches and pulls the stats
	 * stores the stats in the statsCache object
	 */
	public void run(){
		
		List<IOFSwitch> switches = new ArrayList<IOFSwitch>(this.statsCache.getSwitches());
		Iterator <IOFSwitch> it = switches.iterator();
		while(it.hasNext()){
			IOFSwitch sw = it.next();
			try{
				
				log.debug("Getting stats for switch: " + sw.getStringId() );
				List<OFStatistics> statsReply = getFlowStatsForSwitch(sw);
				statsCache.setFlowCache(sw.getId(), statsReply);
				HashMap<Short, OFStatistics> portStatsReply = getPortStatsForSwitch(sw);
				statsCache.setPortCache(sw.getId(), portStatsReply);
				
				//check for anything that has expired
				List<FlowTimeout> timeouts = statsCache.getPossibleExpiredFlows(sw.getId());
				this.updateExpire(timeouts, sw.getId());
				statsCache.checkExpireFlows(sw.getId());
			}catch(Exception e){
				log.error("Exception thrown in Stat collection handler...");
				log.error(e.getMessage());
				StackTraceElement[] trace = e.getStackTrace();
				for(int i=0; i< trace.length; i++){
					log.error(trace[i].toString());
				}
				statsCache.clearFlowCache(sw.getId());
			}

		}	
		
		
		//write our cache to disk!
		FileOutputStream fos;
		ObjectOutputStream oos;
		try {
			fos = new FileOutputStream(cacheFile);
			oos = new ObjectOutputStream(fos);
			statsCache.writeObject(oos);
			oos.flush();
			oos.close();
		} catch (IOException e) {
			e.printStackTrace();
			log.error("Error occured writing statsCache");
		}
		
		
	}
	
	public void updateExpire(List<FlowTimeout> timeouts, Long switchId){
		for(FlowTimeout timeout : timeouts){
			if(!timeout.isHard()){
				for(OFStatistics stat : statsCache.getSwitchFlowStats(switchId)){
					OFFlowStatisticsReply flowStat = (OFFlowStatisticsReply) stat;
					if(flowStat.getMatch().equals(timeout.getFlow().getMatch())){
						if(timeout.getPacketCount() == flowStat.getPacketCount()){
							//hasn't changed... so it has been idle
						}else{
							timeout.setPacketCount(flowStat.getPacketCount());
							timeout.updateLastUsed();
						}
					}
				}
			}
		}
	}
	

	/**
	 * 
	 * 
	 */
	
	public List<OFStatistics> getSlicedFlowStats(Long switchId, String sliceName){
		return statsCache.getSlicedFlowStats(switchId, sliceName);
	}
	
	public void clearCache(Long switchId){
		statsCache.clearFlowCache(switchId);
	}
	

	/**
	 * returns the last cached stats for the switch
	 * @param switchId
	 * @return
	 */
	
	public List<OFStatistics> getSwitchStats(Long switchId){
		return statsCache.getSwitchFlowStats(switchId);
	}
	
	public OFStatistics getPortStats(Long switchId, short portId){
		return statsCache.getPortStats(switchId, portId);
	}
	
	public HashMap<Short, OFStatistics> getPortStats(Long switchId){
		return statsCache.getPortStats(switchId);
	}
	
	/**
	 * Retrieves FlowStats for everything on the switch
	 * and returns them.
	 * @param sw
	 * @return List of OFStatistics objects
	 */
	private List<OFStatistics> getFlowStatsForSwitch(IOFSwitch sw){
		List <OFStatistics> statsReply = new ArrayList<OFStatistics>();
		List <OFStatistics> values = null;
		Future<List<OFStatistics>> future;
		// Statistics request object for getting flows
        OFStatisticsRequest req = new OFStatisticsRequest();
	    req.setStatisticType(OFStatisticsType.FLOW);
	    int requestLength = req.getLengthU();
    	OFFlowStatisticsRequest specificReq = new OFFlowStatisticsRequest();
        specificReq.setMatch(new OFMatch().setWildcards(0xffffffff));
        specificReq.setTableId((byte) 0xff);
        specificReq.setOutPort((short)-1);
        req.setStatistics(Collections.singletonList((OFStatistics)specificReq));
        requestLength += specificReq.getLength();
        req.setLengthU(requestLength);
        
        try {
        	future = sw.queryStatistics(req);
        	log.debug(future.toString());
        	values = future.get(10, TimeUnit.SECONDS);
        	log.debug(values.toString());
        	if(values != null){
            	for(OFStatistics stat : values){
            		log.debug("Adding Stat");
            		statsReply.add(stat);
            	}
            }
        } catch (Exception e) {
            log.error("Failure retrieving statistics from switch " + sw, e);
        }
        log.debug("Stats cached for switch: " + sw.getId() + ". Total flows cached: " + statsReply.size());
        return statsReply;
	}
	
	private HashMap<Short, OFStatistics> getPortStatsForSwitch(IOFSwitch sw){
		List <OFStatistics> values = null;
		Future<List<OFStatistics>> future;
		// Statistics request object for getting flows
        OFStatisticsRequest req = new OFStatisticsRequest();
	    req.setStatisticType(OFStatisticsType.PORT);
	    int requestLength = req.getLengthU();
    	OFPortStatisticsRequest specificReq = new OFPortStatisticsRequest();
        specificReq.setPortNumber(OFPort.OFPP_NONE.getValue());
        req.setStatistics(Collections.singletonList((OFStatistics)specificReq));
        requestLength += specificReq.getLength();
        req.setLengthU(requestLength);
        HashMap<Short, OFStatistics> statsReply = new HashMap<Short, OFStatistics>();
        
        try {
        	future = sw.queryStatistics(req);
        	log.debug(future.toString());
        	values = future.get(10, TimeUnit.SECONDS);
        	log.debug(values.toString());
        	if(values != null){
            	for(OFStatistics stat : values){
            		OFPortStatisticsReply portStat = (OFPortStatisticsReply) stat;
            		log.debug("Adding Stat");
            		statsReply.put(portStat.getPortNumber(), stat);
            	}
            }
        } catch (Exception e) {
            log.error("Failure retrieving statistics from switch " + sw, e);
        }
        log.debug("Stats cached for switch: " + sw.getId() + ". Total ports stats cached: " + statsReply.size());
        return statsReply;
	}
	public void addFlowCache(long switchId, String sliceName, OFFlowMod flowMod, List<OFFlowMod> flows) {
		this.statsCache.addFlowMod(switchId, sliceName, flowMod,flows);
	}
	public void delFlowCache(long switchId, String sliceName, OFFlowMod flowMod, List<OFFlowMod> flows){
		this.statsCache.delFlowMod(switchId,  sliceName, flowMod, flows);
	}
	
	//load the cache from disk
	//this only happens during startup of FSFW
	public void loadCache(){
		try{
			FileInputStream fis = new FileInputStream(cacheFile);
			ObjectInputStream ois = new ObjectInputStream(fis);
			statsCache.readObject(ois);
		}catch(IOException e){
			
		}
	}
	
}