/*
 * Copyright (c) 2012 Crossing-Tech TM Switzerland. All right reserved.
 * Copyright (c) 2012, RiSD Laboratory, EPFL, Switzerland.
 *
 * Author: Simon Bliudze, Alina Zolotukhina, Anastasia Mavridou, and Radoslaw Szymanek
 * Date: 10/15/12
 */

package org.bip.spec;

import java.util.ArrayList;
import java.util.List;

import org.apache.camel.CamelContext;
import org.apache.camel.CamelContextAware;
import org.apache.camel.Exchange;
import org.apache.camel.Route;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.spi.RoutePolicy;
import org.bip.annotations.ExecutableBehaviour;
import org.bip.api.Executor;
import org.bip.api.PortType;
import org.bip.executor.BehaviourBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/**
 * [DONE] ensure guards atomicity There is a potential problem that isFinished
 * and notFinished are executing on alive separate thread and they both can give
 * answer true if called in order notFinished, isFinished and in between the
 * calls the thread changes. How we can protect against it, or discover the
 * situation and re-evaluate guards again? State machine assumes some invariants
 * among guards which may be violated due to race conditions and unstable state
 * of Camel context when queried.
 */

public class SwitchableRouteExecutableBehavior implements CamelContextAware, InitializingBean, DisposableBean {

	@ExecutableBehaviour
    public BehaviourBuilder getExecutableBehavior() throws NoSuchMethodException {

		BehaviourBuilder behaviourBuilder = new BehaviourBuilder();
				
		behaviourBuilder.setComponentType(this.getClass().getCanonicalName());
        //String componentType = this.getClass().getCanonicalName();

        String currentState = "off";

        behaviourBuilder.setInitialState(currentState);

        // [Port=(id = end, specType = null, type = spontaneous),
        behaviourBuilder.addPort("end", PortType.spontaneous, this.getClass());

        // Port=(id = on, specType = null, type = enforceable),
        behaviourBuilder.addPort("on", PortType.enforceable, this.getClass());

        // Port=(id = off, specType = null, type = enforceable),
        behaviourBuilder.addPort("off", PortType.enforceable, this.getClass());

        // Port=(id = finished, specType = null, type = enforceable)]
        behaviourBuilder.addPort("finished", PortType.enforceable, this.getClass());
        
        //ArrayList<TransitionImpl> allTransitions = new ArrayList<TransitionImpl>();

        // ExecutorTransition=(name = on, source = off -> target = on, guard = , method = public void org.bip.spec.SwitchableRoute.startRoute() throws java.lang.Exception),
        behaviourBuilder.addTransitionAndStates("on","off", "on",  "", SwitchableRouteExecutableBehavior.class.getMethod("startRoute"));

        // ExecutorTransition=(name = off, source = on -> target = wait, guard = , method = public void org.bip.spec.SwitchableRoute.stopRoute() throws java.lang.Exception),
        behaviourBuilder.addTransitionAndStates("off","on", "wait",  "", SwitchableRouteExecutableBehavior.class.getMethod("stopRoute"));

        // ExecutorTransition=(name = end, source = wait -> target = done, guard = !isFinished, method = public void org.bip.spec.SwitchableRoute.spontaneousEnd() throws java.lang.Exception),
        behaviourBuilder.addTransitionAndStates("end","wait", "done",  "!isFinished", SwitchableRouteExecutableBehavior.class.getMethod("spontaneousEnd"));

        // ExecutorTransition=(name = , source = wait -> target = done, guard = isFinished, method = public void org.bip.spec.SwitchableRoute.internalEnd() throws java.lang.Exception),
        behaviourBuilder.addTransitionAndStates("","wait", "done",  "isFinished", SwitchableRouteExecutableBehavior.class.getMethod("internalEnd"));

        // ExecutorTransition=(name = finished, source = done -> target = off, guard = , method = public void org.bip.spec.SwitchableRoute.finishedTransition() throws java.lang.Exception)]
        behaviourBuilder.addTransitionAndStates( "finished","done", "off", "", SwitchableRouteExecutableBehavior.class.getMethod("finishedTransition"));

        // [off, on, wait, done]
        
        behaviourBuilder.addState("off");
        behaviourBuilder.addState("on");
        behaviourBuilder.addState("wait");
        behaviourBuilder.addState("done");

        // [Guard=(name = isFinished, method = isFinished)]
    //    ArrayList<Guard> guards = new ArrayList<Guard>();
	//	guards.add(new GuardImpl("isFinished", this.getClass().getMethod("isFinished")));

		behaviourBuilder.addGuard("isFinished", this.getClass().getMethod("isFinished"));
		
/*        BehaviourBuilder behaviourBuilder = new BehaviourBuilder(componentType,
                currentState,
                allTransitions, allPorts, states, guards, this);
*/
		
		behaviourBuilder.setComponent(this);
        return behaviourBuilder;
    }


    public ModelCamelContext camelContext;

    public String routeId;

    private boolean workDone = false;
    Logger logger = LoggerFactory.getLogger(SwitchableRouteExecutableBehavior.class);

    private Executor executor;
    private RoutePolicy notifier;

    public void setCamelContext(CamelContext camelContext) {
    	// TODO, find a better was to obtain ModelCamelContext, do not relay on DefaultCamelContext being injected.
        this.camelContext = (ModelCamelContext)camelContext;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    public CamelContext getCamelContext() {
        return camelContext;
    }

    public SwitchableRouteExecutableBehavior(String routeId) {
        this.routeId = routeId;
    }

    /**
     * In some cases you may want to execute
     */
    public void workDone() {
        logger.debug("Port handler for end port is executing.");
        workDone = true;
    }

    /*
      * Check what are the conditions for throwing the exception.
      */
    public void stopRoute() throws Exception {
        logger.debug("Stop transition handler for {} is being executed.", routeId);
        camelContext.suspendRoute(routeId);

    }

    public void spontaneousEnd() throws Exception {
        logger.info("Received end notification for the route {}.", routeId);
    }

    public void internalEnd() throws Exception {
        logger.info("Transitioning to done state directly since work within {} is already finished.", routeId);
    }

    public void finishedTransition() throws Exception {
        logger.debug("Transitioning to off state from done for {}.", routeId);
    }

    public void startRoute() throws Exception {
        logger.debug("Start transition handler for {} is being executed.", routeId);
        camelContext.resumeRoute(routeId);
    }

    public boolean isFinished() {
        return camelContext.getInflightRepository().size(routeId) == 0;
    }

    public void afterPropertiesSet() throws Exception {
        RouteDefinition routeDefinition = camelContext.getRouteDefinition(routeId);

        if (routeDefinition == null)
            throw new IllegalStateException("The route with a given id " + routeId + " can not be found in the CamelContext.");

		if (executor == null)
			throw new IllegalStateException("BIP Executor for handling this bip spec has not been injected thus no spontaneous even notification can be established.");

        List<RoutePolicy> routePolicyList = routeDefinition.getRoutePolicies();

        if (routePolicyList == null) {
            routePolicyList = new ArrayList<RoutePolicy>();
        }
        final Executor finalExecutor = executor;
        notifier = new RoutePolicy() {

            public void onInit(Route route) {
            }

            public void onExchangeBegin(Route route, Exchange exchange) {
            }

            public void onExchangeDone(Route route, Exchange exchange) {
                finalExecutor.inform("end");
            }

			@Override
			public void onRemove(Route arg0) {
			}

			@Override
			public void onResume(Route arg0) {
			}

			@Override
			public void onStart(Route arg0) {
			}

			@Override
			public void onStop(Route arg0) {
			}

			@Override
			public void onSuspend(Route arg0) {
			}
        };

        routePolicyList.add(notifier);
        routeDefinition.setRoutePolicies(routePolicyList);

    }

    public void destroy() throws Exception {

        RouteDefinition routeDefinition = camelContext.getRouteDefinition(routeId);

        if (routeDefinition != null) {

            List<RoutePolicy> routePolicyList = routeDefinition.getRoutePolicies();

            routePolicyList.remove(notifier);
            routeDefinition.setRoutePolicies(routePolicyList);

        }

    }

}
