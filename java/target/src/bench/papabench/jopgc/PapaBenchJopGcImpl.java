/* $Id$
 * 
 * This file is a part of jPapaBench providing a Java implementation 
 * of PapaBench project.
 * Copyright (C) 2010  Michal Malohlava <michal.malohlava_at_d3s.mff.cuni.cz>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 * 
 */
package papabench.jopgc;

import joprt.RtThread;
import com.jopdesign.sys.GC;
import com.jopdesign.io.*;

import papabench.core.autopilot.conf.PapaBenchAutopilotConf.AltitudeControlTaskConf;
import papabench.core.autopilot.conf.PapaBenchAutopilotConf.ClimbControlTaskConf;
import papabench.core.autopilot.conf.PapaBenchAutopilotConf.LinkFBWSendTaskConf;
import papabench.core.autopilot.conf.PapaBenchAutopilotConf.NavigationTaskConf;
import papabench.core.autopilot.conf.PapaBenchAutopilotConf.RadioControlTaskConf;
import papabench.core.autopilot.conf.PapaBenchAutopilotConf.ReportingTaskConf;
import papabench.core.autopilot.conf.PapaBenchAutopilotConf.StabilizationTaskConf;
import papabench.core.autopilot.modules.AutopilotModule;
import papabench.core.autopilot.tasks.handlers.AltitudeControlTaskHandler;
import papabench.core.autopilot.tasks.handlers.ClimbControlTaskHandler;
import papabench.core.autopilot.tasks.handlers.LinkFBWSendTaskHandler;
import papabench.core.autopilot.tasks.handlers.NavigationTaskHandler;
import papabench.core.autopilot.tasks.handlers.RadioControlTaskHandler;
import papabench.core.autopilot.tasks.handlers.ReportingTaskHandler;
import papabench.core.autopilot.tasks.handlers.StabilizationTaskHandler;
import papabench.core.bus.SPIBusChannel;
import papabench.core.commons.data.FlightPlan;
import papabench.core.fbw.conf.PapaBenchFBWConf.CheckFailsafeTaskConf;
import papabench.core.fbw.conf.PapaBenchFBWConf.CheckMega128ValuesTaskConf;
import papabench.core.fbw.conf.PapaBenchFBWConf.SendDataToAutopilotTaskConf;
import papabench.core.fbw.conf.PapaBenchFBWConf.TestPPMTaskConf;
import papabench.core.fbw.modules.FBWModule;
import papabench.core.fbw.tasks.handlers.CheckFailsafeTaskHandler;
import papabench.core.fbw.tasks.handlers.CheckMega128ValuesTaskHandler;
import papabench.core.fbw.tasks.handlers.SendDataToAutopilotTaskHandler;
import papabench.core.fbw.tasks.handlers.TestPPMTaskHandler;
import papabench.core.simulator.conf.PapaBenchSimulatorConf.SimulatorFlightModelTaskConf;
import papabench.core.simulator.conf.PapaBenchSimulatorConf.SimulatorGPSTaskConf;
import papabench.core.simulator.conf.PapaBenchSimulatorConf.SimulatorIRTaskConf;
import papabench.core.simulator.model.FlightModel;
import papabench.core.simulator.tasks.handlers.SimulatorFlightModelTaskHandler;
import papabench.core.simulator.tasks.handlers.SimulatorGPSTaskHandler;
import papabench.core.simulator.tasks.handlers.SimulatorIRTaskHandler;
import papabench.jopgc.commons.tasks.JopPeriodicTask;

/**
 * JOP based implementation of PapaBench.
 * 
 * @author Michal Malohlava
 *
 */
public class PapaBenchJopGcImpl implements JopPapaBench {
	
	private AutopilotModule autopilotModule;
	private FBWModule fbwModule;	
	private FlightPlan flightPlan;
	
	// TODO move this to PapaBench core
	private static final int AUTOPILOT_TASKS_COUNT = 7;
	private static final int FBW_TASKS_COUNT = 4;
	private static final int SIMULATOR_TASKS_COUNT = 3;
	private static final int TOTAL_TASKS_COUNT = AUTOPILOT_TASKS_COUNT + FBW_TASKS_COUNT + SIMULATOR_TASKS_COUNT;
	
	private JopPeriodicTask[] autopilotTasks;
	private JopPeriodicTask[] fbwTasks;
	private JopPeriodicTask[] simulatorTasks;
	
	public AutopilotModule getAutopilotModule() {
		return autopilotModule;
	}

	public FBWModule getFBWModule() {
		return fbwModule;
	}

	public void setFlightPlan(FlightPlan flightPlan) {
		this.flightPlan = flightPlan;		
	}

	public void init() {	
		// Allocate and initialize global objects: 
		//  - MC0 - autopilot
		autopilotModule = PapaBenchJopFactory.createAutopilotModule(this);
				
		//  - MC1 - FBW
		fbwModule = PapaBenchJopFactory.createFBWModule();		
		
		// Create & configure SPIBusChannel and connect both PapaBench modules
		SPIBusChannel spiBusChannel = PapaBenchJopFactory.createSPIBusChannel();
		spiBusChannel.init();
		autopilotModule.setSPIBus(spiBusChannel.getMasterEnd()); // = MC0: SPI master mode
		fbwModule.setSPIBus(spiBusChannel.getSlaveEnd()); // = MC1: SPI slave mode
		
		// setup flight plan
		// no assert in JOP
		// assert(this.flightPlan != null);
		autopilotModule.getNavigator().setFlightPlan(this.flightPlan);
		
		// initialize both modules - if the modules are badly initialized the runtime exception is thrown
		autopilotModule.init();
		fbwModule.init();
		
		// Register interrupt handlers
		// TODO
		
		// Register period threads
		createAutopilotTasks(autopilotModule);
		createFBWTasks(fbwModule);
		
		// Create a flight simulator
		FlightModel flightModel = PapaBenchJopFactory.createSimulator();
		flightModel.init();
		
		// Register simulator tasks
		createSimulatorTasks(flightModel, autopilotModule, fbwModule);
	}
	
	protected void createAutopilotTasks(AutopilotModule autopilotModule) {
		autopilotTasks = new JopPeriodicTask[AUTOPILOT_TASKS_COUNT];
		autopilotTasks[0] = new JopPeriodicTask(new AltitudeControlTaskHandler(autopilotModule), AltitudeControlTaskConf.PRIORITY, AltitudeControlTaskConf.RELEASE_MS, AltitudeControlTaskConf.PERIOD_MS, AltitudeControlTaskConf.CORE, AltitudeControlTaskConf.NAME);
		autopilotTasks[1] = new JopPeriodicTask(new ClimbControlTaskHandler(autopilotModule), ClimbControlTaskConf.PRIORITY, ClimbControlTaskConf.RELEASE_MS, ClimbControlTaskConf.PERIOD_MS, ClimbControlTaskConf.CORE, ClimbControlTaskConf.NAME);
		autopilotTasks[2] = new JopPeriodicTask(new LinkFBWSendTaskHandler(autopilotModule), LinkFBWSendTaskConf.PRIORITY, LinkFBWSendTaskConf.RELEASE_MS, LinkFBWSendTaskConf.PERIOD_MS, LinkFBWSendTaskConf.CORE, LinkFBWSendTaskConf.NAME);
		autopilotTasks[3] = new JopPeriodicTask(new NavigationTaskHandler(autopilotModule), NavigationTaskConf.PRIORITY, NavigationTaskConf.RELEASE_MS, NavigationTaskConf.PERIOD_MS, NavigationTaskConf.CORE, NavigationTaskConf.NAME);
		autopilotTasks[4] = new JopPeriodicTask(new RadioControlTaskHandler(autopilotModule), RadioControlTaskConf.PRIORITY, RadioControlTaskConf.RELEASE_MS, RadioControlTaskConf.PERIOD_MS, RadioControlTaskConf.CORE, RadioControlTaskConf.NAME);
		autopilotTasks[5] = new JopPeriodicTask(new ReportingTaskHandler(autopilotModule), ReportingTaskConf.PRIORITY, ReportingTaskConf.RELEASE_MS, ReportingTaskConf.PERIOD_MS, ReportingTaskConf.CORE, ReportingTaskConf.NAME);
		// StabilizationTask allocates messages which are sent to FBW unit
		autopilotTasks[6] = new JopPeriodicTask(new StabilizationTaskHandler(autopilotModule), StabilizationTaskConf.PRIORITY, StabilizationTaskConf.RELEASE_MS, StabilizationTaskConf.PERIOD_MS, StabilizationTaskConf.CORE, StabilizationTaskConf.NAME);
	}
	
	protected void createFBWTasks(FBWModule fbwModule) {
		fbwTasks = new JopPeriodicTask[FBW_TASKS_COUNT];
		fbwTasks[0] = new JopPeriodicTask(new CheckFailsafeTaskHandler(fbwModule), CheckFailsafeTaskConf.PRIORITY, CheckFailsafeTaskConf.RELEASE_MS, CheckFailsafeTaskConf.PERIOD_MS, CheckFailsafeTaskConf.CORE, CheckFailsafeTaskConf.NAME);

		fbwTasks[1] = new JopPeriodicTask(new CheckMega128ValuesTaskHandler(fbwModule), CheckMega128ValuesTaskConf.PRIORITY, CheckMega128ValuesTaskConf.RELEASE_MS, CheckMega128ValuesTaskConf.PERIOD_MS, CheckMega128ValuesTaskConf.CORE, CheckMega128ValuesTaskConf.NAME);

		fbwTasks[2] = new JopPeriodicTask(new SendDataToAutopilotTaskHandler(fbwModule), SendDataToAutopilotTaskConf.PRIORITY, SendDataToAutopilotTaskConf.RELEASE_MS, SendDataToAutopilotTaskConf.PERIOD_MS, SendDataToAutopilotTaskConf.CORE, SendDataToAutopilotTaskConf.NAME);

		fbwTasks[3] = new JopPeriodicTask(new TestPPMTaskHandler(fbwModule), TestPPMTaskConf.PRIORITY, TestPPMTaskConf.RELEASE_MS, TestPPMTaskConf.PERIOD_MS, TestPPMTaskConf.CORE, TestPPMTaskConf.NAME);
	}
	
	protected void createSimulatorTasks(FlightModel flightModel, AutopilotModule autopilotModule, FBWModule fbwModule) {
		simulatorTasks = new JopPeriodicTask[SIMULATOR_TASKS_COUNT];

		simulatorTasks[0] = new JopPeriodicTask(new SimulatorFlightModelTaskHandler(flightModel,autopilotModule,fbwModule), SimulatorFlightModelTaskConf.PRIORITY, SimulatorFlightModelTaskConf.RELEASE_MS, SimulatorFlightModelTaskConf.PERIOD_MS, SimulatorFlightModelTaskConf.CORE, SimulatorFlightModelTaskConf.NAME);

		simulatorTasks[1] = new JopPeriodicTask(new SimulatorGPSTaskHandler(flightModel,autopilotModule), SimulatorGPSTaskConf.PRIORITY, SimulatorGPSTaskConf.RELEASE_MS, SimulatorGPSTaskConf.PERIOD_MS, SimulatorGPSTaskConf.CORE, SimulatorGPSTaskConf.NAME);

		simulatorTasks[2] = new JopPeriodicTask(new SimulatorIRTaskHandler(flightModel,autopilotModule), SimulatorIRTaskConf.PRIORITY, SimulatorIRTaskConf.RELEASE_MS, SimulatorIRTaskConf.PERIOD_MS, SimulatorIRTaskConf.CORE, SimulatorIRTaskConf.NAME);
	}

	public void start() {
		for (int i = 0; i < SIMULATOR_TASKS_COUNT; i++) {
			start(simulatorTasks[i]);			
		}
		for (int i = 0; i < FBW_TASKS_COUNT; i++) {
			start(fbwTasks[i]);
		}		
		for (int i = 0; i < AUTOPILOT_TASKS_COUNT; i++) {
			start(autopilotTasks[i]);
		}

		SysDevice sys = IOFactory.getFactory().getSysDevice();

		// the GC thread
		GC.GCThread gcThread = new GC.GCThread(1, 199*1000);
		gcThread.setProcessor(0);

		// the root scanning events
		new GC.ScanEvent(20, 1000*1000, 0);
		new GC.ScanEvent(90, 1000*1000, 1);
		new GC.ScanEvent(90, 1000*1000, 2);
		// new GC.ScanEvent(1,  1000*1000, 3);
		// new GC.ScanEvent(1,  1000*1000, 4);
		new GC.ScanEvent(31, 1000*1000, 5);
		new GC.ScanEvent(30, 1000*1000, 6);
		new GC.ScanEvent(90, 1000*1000, 7);

		GC.setConcurrent();

		RtThread.startMission();

		for(;;); /* loop forever */
	}
	
	protected void start(JopPeriodicTask pjPeriodicTask) {
		// this is a noop on JOP
	}

	public static boolean halt = false;

	@Override
	public void shutdown() {
		// there is no shutdown in a JOP RT thread system
		// System.out.println("Shutdown request");
		halt = true;
	}
}