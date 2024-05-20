/**
 * 
 */
package com.perelens.samples.cloud;

import java.util.Arrays;
import java.util.Collections;

import com.perelens.engine.api.AbstractEvent;
import com.perelens.engine.api.AbstractEventConsumer;
import com.perelens.engine.api.Event;
import com.perelens.engine.api.EventFilter;
import com.perelens.engine.api.EventGenerator;
import com.perelens.engine.api.EventType;
import com.perelens.engine.core.AbstractEventEvaluator;
import com.perelens.simulation.api.Function;
import com.perelens.simulation.api.FunctionInfo;
import com.perelens.simulation.core.CoreDistributionProvider;
import com.perelens.simulation.core.CoreSimulationBuilder;
import com.perelens.simulation.failure.FunctionKofN;
import com.perelens.simulation.failure.RandomFailureFunction;
import com.perelens.simulation.failure.consumers.AvailabilityConsumer;
import com.perelens.simulation.random.RanluxProvider;
import com.perelens.simulation.statistics.SampledStatistic;
import com.perelens.simulation.utils.Relationships;

/**
 * Copyright 2024 Steven Branda
   Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" 
   BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing 
   permissions and limitations under the License

 * 
 * This class contains sample code for simulating a standard mult-tier web application hosted using common cloud services with 
 * minute resolution.
 * 
 * The sample code calculates failure risk and cost, the goal being to show how simulation can be used to optimize multiple characteristics
 * of the system because it operates on a real world view of the system.
 * 
 * Using SLA commitments for expected availability is done here for simplicity, and likely does not reflect real world performance.
 * Real world applications should use historical performance to update expectations over time.
 * 
 * @author Steve Branda
 *
 */
public class CloudWebApp {

	
	//Main sample code here
	public static void main (String[] args) {
		
		var builder = new CoreSimulationBuilder();
		var distributionProvider = new CoreDistributionProvider();
		var random_provider = new RanluxProvider(101010101);
		builder.setRandomProvider(random_provider);
		
		long hours_per_month = 730;
		long minutes_per_hour = 60;
		long minutes_per_month = hours_per_month * minutes_per_hour;
		long minutes_per_day = 60*24;
		long minutes_per_week = minutes_per_day*7;
		long minutes_per_year = minutes_per_week * 52;
		
		//Create the Database Tier failure node using SLA from Google Cloud SQL Database Enterprise Edition with HA service (May 2024)
		double database_availability = percent(99.95);  //Availability target from SLA
		double database_meanRepairTime_minutes = 120;    //Rough estimate from public outage data at https://status.cloud.google.com/summary
		double database_meanTimeBetweenFailure_minutes 
			= Relationships.getMeanTimeBetweenFailure(database_availability, database_meanRepairTime_minutes);
		
		//Not enough data at this point to suggest a more specific distribution, so use exponential distribution.
		var database_failure_distribution = distributionProvider.exponential(database_meanTimeBetweenFailure_minutes);
		var database_repair_distribution = distributionProvider.exponential(database_meanRepairTime_minutes);
		
		var database = new RandomFailureFunction("database",database_failure_distribution, database_repair_distribution);
		
		
		//Add the Database Tier failure risk to the Simulation
		var database_ref = builder.addFunction(database);
		
		
		//Create Database Tier cost node using May 2024 pricing example at https://cloud.google.com/sql/docs/mysql/pricing-examples
		//Highly available production database with 4 cpu, 26 gb RAM, 3 year commit, $273.55/month
		var database_cost = new PeriodicCost("database cost",273.55/730,minutes_per_hour); //converted to hourly cost
		var database_cost_ref = builder.addFunction(database_cost); //add to simulation
			
		//Create the Application Server Tier failure node using SLA from Google Cloud Compute Engine SLA May 2024
		double vm_availability = percent(99.9);  //Availability target from SLA
		double vm_meanRepairTime_minutes = 120;  //Rough estimate from public outage data at https://status.cloud.google.com/summary
		double vm_meanTimeBetweenFailure_minutes
			= Relationships.getMeanTimeBetweenFailure(vm_availability, vm_meanRepairTime_minutes);
		
		//Not enough data at this point to suggest a more specific distribution, so use exponential distribution.
		var vm_failure_distribution = distributionProvider.exponential(vm_meanTimeBetweenFailure_minutes);
		var vm_repair_distribution = distributionProvider.exponential(vm_meanRepairTime_minutes);
		
		var appServerHost = new RandomFailureFunction("application server host",vm_failure_distribution,vm_repair_distribution);
		
		//Add the App Server host tier failure risk to the Simulation
		var appServerHost_ref = builder.addFunction(appServerHost);
		
		//Create the application server function that depends on the app server host and database
		var appServer = new FunctionKofN("application server", 2, 2);
		builder.addFunction(appServer).addDependency(appServerHost_ref).addDependency(database_ref);
		
		//Create App Server tier cost node using May 2024 pricing from https://cloud.google.com/compute/vm-instance-pricing
		var appserver_cost = new PeriodicCost("application server cost",0.189544,minutes_per_hour); //n4-standard-4 $0.189544/hour
		builder.addFunction(appserver_cost);  //add to simulation
		
		//Create a runnable simulation from the builder with 4 threads of parallelism
		
		var simulation = builder.createSimulation(4);
		
		//Create the consumers to collect the useful predictions
		var availability = new AvailabilityConsumer("system availability");
		simulation.subscribeToEvents(availability, Collections.singletonList("application server"));
		
		//Cost can be collected with any level of granularity larger than the hourly events
		var monthly_cost = new CostConsumer("monthly cost",minutes_per_month);
		simulation.subscribeToEvents(monthly_cost, Arrays.asList(new String[] {"application server cost", "database cost"}));
		
		var weekly_cost = new CostConsumer("weekly cost",minutes_per_week);
		simulation.subscribeToEvents(weekly_cost, Arrays.asList(new String[] {"application server cost", "database cost"}));
		
		var quartly_cost = new CostConsumer("quartly cost",minutes_per_month * 3);
		simulation.subscribeToEvents(quartly_cost, Arrays.asList(new String[] {"application server cost", "database cost"}));
		
		
		long simulation_duration = minutes_per_year * 1000; //simulate 1000 years
		
		simulation.start(simulation_duration);
		try {
			simulation.join();
		} catch (Throwable e) {
			e.printStackTrace();
		}
		simulation.destroy();
		
		System.out.println("Availability: " + availability.getAvailability() * 100.0 + "%");
		System.out.println("Weekly Cost: " + weekly_cost.getCosts().getMean());
		System.out.println("Montly Cost: " + monthly_cost.getCosts().getMean());
		System.out.println("Quartly Cost: " + quartly_cost.getCosts().getMean());
		
	}
	
	
	//Extensions for Cost Simulation
	public static class CostEvent extends AbstractEvent{

		public final static EventType TYPE = new EventType() {

			@Override
			public String name() {
				return "Cost Event";
			}};
			
		private double cost;
			
		protected CostEvent(String producerId, long time, long ordinal, double cost) {
			super(producerId, TYPE, time, ordinal);
			this.cost = cost;
		}
		
		public double getCost() {
			return cost;
		}
		
	}
	
	public static class PeriodicCost extends AbstractEventEvaluator implements Function{

		private long timeUnitsPerPeriod;
		private double cost;
		private long nextEventTime;
		
		protected PeriodicCost(String id, double cost, long timeUnitsPerPeriod) {
			super(id);
			this.timeUnitsPerPeriod = timeUnitsPerPeriod;
			this.cost = cost;
		}

		@Override
		protected void preProcess() {
			if (0 == getTimeProcessed()) {
				//first invocation so initialize
				nextEventTime = timeUnitsPerPeriod;
				registerCallbackTime(nextEventTime);
			}
		}

		@Override
		public EventGenerator copy() {
			//Object copy boiler plate.  Not used unless resolving circular references.
			var toReturn = new PeriodicCost(this.getId(),cost,timeUnitsPerPeriod);
			this.syncInternalState(toReturn);
			return toReturn;
		}

		@Override
		protected void process(Event curEvent) {
			if (nextEventTime == getTimeProcessed()) {
				var event = new CostEvent(getId(),nextEventTime,this.getNextOrdinal(),cost);
				this.raiseEvent(event);
				nextEventTime += timeUnitsPerPeriod;
				registerCallbackTime(nextEventTime);
			}
		}

		@Override
		public void initiate(FunctionInfo info) {
		}
	}
	
	public static class CostConsumer extends AbstractEventConsumer implements EventFilter{

		private final long periodUnits;
		private long periodMarker;
		private SampledStatistic costs = new SampledStatistic();
		private double total = 0;
		
		
		protected CostConsumer(String id, long timeUnitsPerPeriod) {
			super(id);
			periodUnits = timeUnitsPerPeriod;
			periodMarker = periodUnits;
		}

		@Override
		public boolean filter(Event event) {
			return event.getType() == CostEvent.TYPE;
		}

		@Override
		protected void processEvent(Event currentEvent) {
			var cur = (CostEvent)currentEvent;
			
			//While loop handles rare case of one or more months without a CostEvent
			while(cur.getTime() >= periodMarker) {
				costs.add(total);
				total = 0;
				periodMarker += periodUnits;
			}
			
			total += cur.getCost();
		}
		
		public SampledStatistic getCosts() {
			return costs;
		}
		
	}
	
	public static double percent(double percent) {
		return percent / 100;
	}

}
