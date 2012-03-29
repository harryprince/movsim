/*
 * Copyright (C) 2010, 2011, 2012 by Arne Kesting, Martin Treiber, Ralph Germ, Martin Budden
 *                                   <movsim.org@gmail.com>
 * -----------------------------------------------------------------------------------------
 * 
 * This file is part of
 * 
 * MovSim - the multi-model open-source vehicular-traffic simulator.
 * 
 * MovSim is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * MovSim is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with MovSim. If not, see <http://www.gnu.org/licenses/>
 * or <http://www.movsim.org>.
 * 
 * -----------------------------------------------------------------------------------------
 */
package org.movsim.simulator.vehicles.lanechange;

import org.movsim.input.model.vehicle.lanechange.LaneChangeInputData;
import org.movsim.simulator.MovsimConstants;
import org.movsim.simulator.roadnetwork.Lane;
import org.movsim.simulator.roadnetwork.LaneSegment;
import org.movsim.simulator.roadnetwork.RoadSegment;
import org.movsim.simulator.vehicles.Vehicle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Class LaneChangeModel.
 */
public class LaneChangeModel {

    /** The Constant logger. */
    final static Logger logger = LoggerFactory.getLogger(LaneChangeModel.class);

    // to avoid flips:
    public static double LANECHANGE_TDELAY_S = 3.0; // delay nach Spurwechsel
    public static double LANECHANGE_TDELAY_FRONT_S = 3.0; // delay nach

    private final boolean withEuropeanRules;

    /** critical speed for kicking in European rules (in m/s) */
    private final double vCritEur;

    private int mandatoryChange = MovsimConstants.NO_CHANGE;

    private Vehicle me;

    private final boolean isInitialized;

    private MOBIL lcModelMOBIL;

    private final LaneChangeInputData lcInputData;

    /**
     * Instantiates a new lane changing model.
     * 
     * @param lcInputData
     *            the lc input data
     */
    public LaneChangeModel(LaneChangeInputData lcInputData) {

        this.lcInputData = lcInputData;
        this.withEuropeanRules = lcInputData.isWithEuropeanRules();
        this.vCritEur = lcInputData.getCritSpeedEuroRules();

        // TODO valid lane change model only if configured by xml
        isInitialized = lcInputData.isInitializedMobilData();

    }

    public LaneChangeModel(Vehicle vehicle, MOBIL lcModelMOBIL) {
        this.lcModelMOBIL = lcModelMOBIL;
        this.me = vehicle;
        this.withEuropeanRules = true;
        this.vCritEur = 5.0;
        this.lcInputData = null;
        isInitialized = true;
    }

    /**
     * Initialize.
     * 
     * @param vehicle
     *            the vehicle
     */
    public void initialize(Vehicle vehicle) {
        this.me = vehicle;
        lcModelMOBIL = (isInitialized) ? new MOBIL(me, lcInputData.getLcMobilData()) : new MOBIL(me);
    }

    /**
     * Checks if is initialized.
     * 
     * @return true, if is initialized
     */
    public boolean isInitialized() {
        return isInitialized;
    }

    public boolean isSafeLaneChange(LaneSegment laneSegment) {
        final Vehicle front = laneSegment.frontVehicle(me);
        final Vehicle back = laneSegment.rearVehicle(me);
        final boolean changeSafe = checkSafetyCriterion(front, back, lcModelMOBIL.getSafeDeceleration());
        return changeSafe;
    }

    private boolean checkSafetyCriterion(Vehicle frontVeh, Vehicle backVeh, double safeDeceleration) {

        // safety incentive (in two steps)
        final double gapFront = me.getNetDistance(frontVeh);
        final double gapBack = (backVeh == null) ? MovsimConstants.GAP_INFINITY : backVeh.getNetDistance(me);

        // check distances
        // negative net distances possible because of different vehicle lengths!
        if (gapFront < lcModelMOBIL.getMinimumGap() || gapBack < lcModelMOBIL.getMinimumGap()) {
            logger.debug("gapFront={}, gapBack={}", gapFront, gapBack);
            return false;
        }

        final double backNewAcc = (backVeh == null) ? 0 : backVeh.getLongitudinalModel().calcAcc(backVeh, me);

        // check security constraint for new follower

        if (backNewAcc <= -safeDeceleration) {
            logger.debug("gapFront = {}, gapBack = {}", gapFront, gapBack);
            logger.debug("backNewAcc={}, bSafe={}", backNewAcc, lcModelMOBIL.getSafeDeceleration());
            return false;
        }

        final double meNewAcc = me.getLongitudinalModel().calcAcc(me, frontVeh);
        if (meNewAcc >= -safeDeceleration) {
            logger.debug("meNewAcc={}, bSafe={}", meNewAcc, lcModelMOBIL.getSafeDeceleration());
            logger.debug("gapFront={}, gapBack={}", gapFront, gapBack);
            logger.debug("backNewAcc={}, bSafe={}", backNewAcc, lcModelMOBIL.getSafeDeceleration());
            return true;
        }
        return false;
    }

    public int determineLaneChangeDirection(RoadSegment roadSegment) {

        final int currentLane = me.getLane();

        // initialize with largest possible deceleration
        double accToLeft = -Double.MAX_VALUE;
        double accToRight = -Double.MAX_VALUE;

        // consider mandatory lane-change to exit
        if (me.exitRoadSegmentId() == roadSegment.id()) {
            if (currentLane == Lane.LANE1) {
                // already in exit lane, so do not move out of it
                return MovsimConstants.NO_CHANGE;
            } else if (currentLane == Lane.LANE2) {
                final LaneSegment laneSegment = roadSegment.laneSegment(Lane.LANE1);
                if (isSafeLaneChange(laneSegment)) {
                    return MovsimConstants.TO_RIGHT;
                }
                return MovsimConstants.NO_CHANGE;
            }
        }

        // consider lane-changing to right-hand side lane (decreasing lane index)
        if (currentLane - 1 >= MovsimConstants.MOST_RIGHT_LANE) {
            final LaneSegment newLaneSegment = roadSegment.laneSegment(currentLane + MovsimConstants.TO_RIGHT);
            if (newLaneSegment.type() == Lane.Type.TRAFFIC) {
                // only consider lane changes into traffic lanes, other lane changes are handled by mandatory lane changing
                accToRight = lcModelMOBIL.calcAccelerationBalance(me, MovsimConstants.TO_RIGHT, roadSegment);
            }
        }

        // consider lane-changing to left-hand side lane (increasing the lane index)
        if (currentLane + 1 < roadSegment.laneCount()) {
            final LaneSegment newLaneSegment = roadSegment.laneSegment(currentLane + MovsimConstants.TO_LEFT);
            if (newLaneSegment.type() == Lane.Type.TRAFFIC) {
                // only consider lane changes into traffic lanes, other lane changes are handled by mandatory lane changing
                accToLeft = lcModelMOBIL.calcAccelerationBalance(me, MovsimConstants.TO_LEFT, roadSegment);
            }
        }

        // decision
        if ((accToRight > 0) || (accToLeft > 0)) {
            logger.debug("accToRight={}, accToLeft={}", accToRight, accToLeft);
            logger.debug("currentLane={}", currentLane);
            if (accToRight > accToLeft) {
                return MovsimConstants.TO_RIGHT;
            }
            return MovsimConstants.TO_LEFT;
        }

        return MovsimConstants.NO_CHANGE;
    }

    public void setMandatoryChange(int incentive) {
        if (incentive == MovsimConstants.NO_CHANGE || incentive == MovsimConstants.TO_RIGHT
                || incentive == MovsimConstants.TO_LEFT) {
            mandatoryChange = incentive;
            System.out.println("LaneChange.setMandatoryChange:" + " mandatoryChange= " + mandatoryChange);
        } else {
            System.exit(-1); // debugging
        }
    }

    public boolean withEuropeanRules() {
        return withEuropeanRules;
    }

    public double vCritEurRules() {
        return vCritEur;
    }
}
