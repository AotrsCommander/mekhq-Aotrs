/*
 * ResolveScenarioTracker.java
 *
 * Copyright (c) 2009 Jay Lawson <jaylawson39 at yahoo.com>. All rights reserved.
 *
 * This file is part of MekHQ.
 *
 * MekHQ is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * MekHQ is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with MekHQ.  If not, see <http://www.gnu.org/licenses/>.
 */
package mekhq.campaign;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

import megamek.client.Client;
import megamek.common.*;
import megamek.common.event.GameVictoryEvent;
import megamek.common.loaders.EntityLoadingException;
import megamek.common.logging.LogLevel;
import megamek.common.options.OptionsConstants;
import mekhq.MekHQ;
import mekhq.Utilities;
import mekhq.campaign.event.PersonBattleFinishedEvent;
import mekhq.campaign.finances.Money;
import mekhq.campaign.finances.Transaction;
import mekhq.campaign.log.ServiceLogger;
import mekhq.campaign.mission.AtBContract;
import mekhq.campaign.mission.AtBScenario;
import mekhq.campaign.mission.BotForce;
import mekhq.campaign.mission.Contract;
import mekhq.campaign.mission.Loot;
import mekhq.campaign.mission.Mission;
import mekhq.campaign.mission.Scenario;
import mekhq.campaign.parts.Armor;
import mekhq.campaign.parts.Part;
import mekhq.campaign.personnel.Person;
import mekhq.campaign.personnel.enums.PersonnelStatus;
import mekhq.campaign.unit.TestUnit;
import mekhq.campaign.unit.Unit;
import mekhq.gui.FileDialogs;

/**
 * This object will be the main workhorse for the scenario
 * resolution wizard. It will keep track of information and be
 * fed back and forth between the various wizards
 * @author Jay Lawson <jaylawson39 at yahoo.com>
 */
public class ResolveScenarioTracker {
    Map<UUID, Entity> entities;
    Map<UUID, List<Entity>> bayLoadedEntities;
    Map<Integer, UUID> idMap;
    Hashtable<UUID, UnitStatus> unitsStatus;
    Hashtable<UUID, UnitStatus> salvageStatus;
    Hashtable<UUID, Crew> pilots;
    Hashtable<UUID, Crew> mia;
    ArrayList<TestUnit> potentialSalvage;
    ArrayList<TestUnit> alliedUnits;
    ArrayList<TestUnit> actualSalvage;
    ArrayList<TestUnit> leftoverSalvage;
    ArrayList<TestUnit> devastatedEnemyUnits;
    ArrayList<Unit> units;
    ArrayList<Loot> potentialLoot;
    ArrayList<Loot> actualLoot;
    Hashtable<UUID, PersonStatus> peopleStatus;
    Hashtable<UUID, PrisonerStatus> prisonerStatus;
    Hashtable<String, String> killCredits;
    Hashtable<UUID, EjectedCrew> ejections;
    Hashtable<UUID, EjectedCrew> enemyEjections;

    /* AtB */
    int contractBreaches = 0;
    int bonusRolls = 0;

    Campaign campaign;
    Scenario scenario;
    Optional<File> unitList = Optional.empty();
    Client client;
    Boolean control;
    private GameVictoryEvent victoryEvent;

    public ResolveScenarioTracker(Scenario s, Campaign c, boolean ctrl) {
        this.scenario = s;
        this.campaign = c;
        this.control = ctrl;
        unitsStatus = new Hashtable<>();
        salvageStatus = new Hashtable<>();
        potentialSalvage = new ArrayList<>();
        alliedUnits = new ArrayList<>();
        actualSalvage = new ArrayList<>();
        leftoverSalvage = new ArrayList<>();
        devastatedEnemyUnits = new ArrayList<>();
        pilots = new Hashtable<>();
        mia = new Hashtable<>();
        units = new ArrayList<>();
        potentialLoot = scenario.getLoot();
        actualLoot = new ArrayList<>();
        peopleStatus = new Hashtable<>();
        prisonerStatus = new Hashtable<>();
        killCredits = new Hashtable<>();
        ejections = new Hashtable<>();
        enemyEjections = new Hashtable<>();
        entities = new HashMap<>();
        bayLoadedEntities = new HashMap<>();
        idMap = new HashMap<>();
        for(UUID uid : scenario.getForces(campaign).getAllUnits()) {
            Unit u = campaign.getUnit(uid);
            if(null != u && null == u.checkDeployment()) {
                units.add(u);
                //assume its missing until we can confirm otherwise
                unitsStatus.put(uid, new UnitStatus(u));
            }
        }
    }

    public void findUnitFile() {
        unitList = FileDialogs.openUnits(null);
    }

    public String getUnitFilePath() {
        return unitList.map(File::getAbsolutePath)
                       .orElse("No file selected");
    }

    public void setClient(Client c) {
        client = c;
    }

    public void processMulFiles() {
        if (unitList.isPresent()) {
            try {
                loadUnitsAndPilots(unitList.get());
            } catch (IOException e) {
                MekHQ.getLogger().error(getClass(), "processMulFiles", e);
            }
        } else {
            initUnitsAndPilotsWithoutBattle();
        }
        checkStatusOfPersonnel();
    }

    private TestUnit generateNewTestUnit(Entity e) {
        // Do some hoops here so that the new mech gets it's old individual paint job!
        String cat = e.getCamoCategory();
        String fn = e.getCamoFileName();
        TestUnit nu = new TestUnit(e, campaign, true);
        nu.getEntity().setCamoCategory(cat);
        nu.getEntity().setCamoFileName(fn);
        /* AtB uses id to track status of allied units */
        if (e.getExternalIdAsString().equals("-1")) {
            UUID id = UUID.randomUUID();
            nu.getEntity().setExternalIdAsString(id.toString());
            nu.setId(id);
        } else {
            nu.setId(UUID.fromString(e.getExternalIdAsString()));
        }

        for (Part part : nu.getParts()) {
            part.setBrandNew(false);
        }
        return nu;
    }

    public void processGame() {
        int pid = client.getLocalPlayer().getId();
        int team = client.getLocalPlayer().getTeam();

        for (Enumeration<Entity> iter = victoryEvent.getEntities(); iter.hasMoreElements();) {
            Entity e = iter.nextElement();
            if(e.getSubEntities().isPresent()) {
                // Sub-entities have their own entry in the VictoryEvent data
                continue;
            }

            entities.put(UUID.fromString(e.getExternalIdAsString()), e);
            //Convenience data
            idMap.put(e.getId(), UUID.fromString(e.getExternalIdAsString()));

            checkForLostLimbs(e, control);
            if(e.getOwnerId() == pid || e.getOwner().getTeam() == team) {
                if(!e.getExternalIdAsString().equals("-1")) {
                    UnitStatus status = unitsStatus.get(UUID.fromString(e.getExternalIdAsString()));
                    if (null == status && scenario instanceof AtBScenario) {
                        status = processAlliedUnit(e);
                    }

                    if(null != status) {
                        boolean lost = (!e.canEscape() && !control) || e.getRemovalCondition() == IEntityRemovalConditions.REMOVE_DEVASTATED;
                        status.assignFoundEntity(e, lost);
                    }
                }
                if(null != e.getCrew()) {
                    if(!e.getCrew().getExternalIdAsString().equals("-1")) {
                        if(!e.getCrew().isEjected() || e instanceof EjectedCrew) {
                            pilots.put(UUID.fromString(e.getCrew().getExternalIdAsString()), e.getCrew());
                        }
                        if(e instanceof EjectedCrew) {
                            ejections.put(UUID.fromString(e.getCrew().getExternalIdAsString()), (EjectedCrew)e);
                        }
                    }
                }
            } else if(e.getOwner().isEnemyOf(client.getLocalPlayer())) {
                if(control) {
                    // Kill credit automatically assigned only if they can't escape
                    if (!e.canEscape()) {
                        Entity killer = victoryEvent.getEntity(e.getKillerId());
                        if(null != killer && killer.getOwnerId() == pid) {
                            //the killer is one of your units, congrats!
                            killCredits.put(e.getDisplayName(), killer.getExternalIdAsString());
                        } else {
                            killCredits.put(e.getDisplayName(), "None");
                        }
                    }
                    if(e instanceof EjectedCrew) {
                        enemyEjections.put(UUID.fromString(e.getCrew().getExternalIdAsString()), (EjectedCrew)e);
                        continue;
                    }

                    if (e instanceof BattleArmor && e.isDestroyed()) {
                        // BA can only be salvaged with a 10+ roll
                        if (Utilities.dice(2, 6) < 10) {
                            continue;
                        }
                    }

                    TestUnit nu = generateNewTestUnit(e);
                    UnitStatus us = new UnitStatus(nu);
                    us.setTotalLoss(false);
                    salvageStatus.put(nu.getId(), us);
                    potentialSalvage.add(nu);
                }
            }
        }

        //If any units ended the game with others loaded in its bays, map those out
        for (Enumeration<Entity> iter = victoryEvent.getEntities(); iter.hasMoreElements();) {
            Entity e = iter.nextElement();
            if (!e.getBayLoadedUnitIds().isEmpty()) {
                List<Entity> cargo = new ArrayList<>();
                for (int id : e.getBayLoadedUnitIds()) {
                    UUID extId = idMap.get(id);
                    if (extId != null) {
                        cargo.add(entities.get(extId));
                    }
                }
                bayLoadedEntities.put(UUID.fromString(e.getExternalIdAsString()), cargo);
            }
        }

        // Utterly destroyed entities
        for (Enumeration<Entity> iter = victoryEvent.getDevastatedEntities(); iter.hasMoreElements();) {
            Entity e = iter.nextElement();
            if(e.getSubEntities().isPresent()) {
                // Sub-entities have their own entry in the VictoryEvent data
                continue;
            }

            entities.put(UUID.fromString(e.getExternalIdAsString()), e);

            if(e.getOwnerId() == pid || e.getOwner().getTeam() == team) {
                if(!e.getExternalIdAsString().equals("-1")) {
                    UnitStatus status = unitsStatus.get(UUID.fromString(e.getExternalIdAsString()));

                    if (null == status && scenario instanceof AtBScenario) {
                        status = processAlliedUnit(e);
                    }

                    if(null != status) {
                        status.assignFoundEntity(e, true);
                    }
                }
            } else {
                Entity killer = victoryEvent.getEntity(e.getKillerId());
                if(null != killer && killer.getOwnerId() == pid) {
                    //the killer is one of your units, congrats!
                    killCredits.put(e.getDisplayName(), killer.getExternalIdAsString());
                } else {
                    killCredits.put(e.getDisplayName(), "None");
                }
            }
        }

        //add retreated units
        for (Enumeration<Entity> iter = victoryEvent.getRetreatedEntities(); iter.hasMoreElements();) {
            Entity e = iter.nextElement();
            if(e.getSubEntities().isPresent()) {
                // Sub-entities have their own entry in the VictoryEvent data
                continue;
            }

            entities.put(UUID.fromString(e.getExternalIdAsString()), e);

            checkForLostLimbs(e, control);
            if(e.getOwnerId() == pid || e.getOwner().getTeam() == team) {
                if(!e.getExternalIdAsString().equals("-1")) {
                    UnitStatus status = unitsStatus.get(UUID.fromString(e.getExternalIdAsString()));
                    if (null == status && scenario instanceof AtBScenario) {
                        status = processAlliedUnit(e);
                    }

                    if(null != status) {
                        status.assignFoundEntity(e, false);
                    }
                }
                if(null != e.getCrew()) {
                    if(!e.getCrew().getExternalIdAsString().equals("-1")) {
                        pilots.put(UUID.fromString(e.getCrew().getExternalIdAsString()), e.getCrew());
                        if(e instanceof EjectedCrew) {
                            ejections.put(UUID.fromString(e.getCrew().getExternalIdAsString()), (EjectedCrew)e);
                        }
                    }
                }
            }
        }

        Enumeration<Entity> wrecks = victoryEvent.getGraveyardEntities();
        while (wrecks.hasMoreElements()) {
            Entity e = wrecks.nextElement();
            if(e.getSubEntities().isPresent()) {
                // Sub-entities have their own entry in the VictoryEvent data
                continue;
            }

            entities.put(UUID.fromString(e.getExternalIdAsString()), e);
            idMap.put(e.getId(), UUID.fromString(e.getExternalIdAsString()));

            checkForLostLimbs(e, control);
            if(e.getOwnerId() == pid || e.getOwner().getTeam() == team) {
                if(!e.getExternalIdAsString().equals("-1")) {
                    UnitStatus status = unitsStatus.get(UUID.fromString(e.getExternalIdAsString()));

                    if (null == status && scenario instanceof AtBScenario) {
                        status = processAlliedUnit(e);
                    }

                    if(null != status) {
                        status.assignFoundEntity(e, !control);
                        if(e instanceof EjectedCrew) {
                            ejections.put(UUID.fromString(e.getExternalIdAsString()), (EjectedCrew)e);
                        }
                    }
                }
                if(null != e.getCrew()) {
                    if(!e.getCrew().getExternalIdAsString().equals("-1")) {
                        if(e instanceof EjectedCrew) {
                            ejections.put(UUID.fromString(e.getCrew().getExternalIdAsString()), (EjectedCrew)e);
                        }
                        if(!e.getCrew().isEjected() || e instanceof EjectedCrew) {
                            if(control) {
                                pilots.put(UUID.fromString(e.getCrew().getExternalIdAsString()), e.getCrew());
                            } else {
                                mia.put(UUID.fromString(e.getCrew().getExternalIdAsString()), e.getCrew());
                            }
                        }
                    }
                }
            } else if(e.getOwner().isEnemyOf(client.getLocalPlayer())) {
                Entity killer = victoryEvent.getEntity(e.getKillerId());
                if(null != killer && killer.getOwnerId() == pid) {
                    //the killer is one of your units, congrats!
                    killCredits.put(e.getDisplayName(), killer.getExternalIdAsString());
                } else {
                    killCredits.put(e.getDisplayName(), "None");
                }
                if(e instanceof EjectedCrew) {
                    enemyEjections.put(UUID.fromString(e.getCrew().getExternalIdAsString()), (EjectedCrew)e);
                    continue;
                }
                if(control) {
                    TestUnit nu = generateNewTestUnit(e);
                    UnitStatus us = new UnitStatus(nu);
                    us.setTotalLoss(false);
                    salvageStatus.put(nu.getId(), us);
                    potentialSalvage.add(nu);
                }
            }
        }
        //If a unit in a bay was destroyed, add it. We still need to deal with the crew
        for (Enumeration<Entity> iter = victoryEvent.getGraveyardEntities(); iter.hasMoreElements();) {
            Entity e = iter.nextElement();
            if (e.getTransportId() != Entity.NONE) {
                UUID trnId = idMap.get(e.getTransportId());
                List<Entity> cargo;
                if (bayLoadedEntities.containsKey(trnId)) {
                    cargo = bayLoadedEntities.get(trnId);
                } else {
                    cargo = new ArrayList<>();
                }
                cargo.add(e);
                bayLoadedEntities.put(trnId, cargo);
            }
        }
        checkStatusOfPersonnel();
    }

    private UnitStatus processAlliedUnit(Entity e) {
        TestUnit nu = generateNewTestUnit(e);
        UnitStatus us = new UnitStatus(nu);
        unitsStatus.put(nu.getId(), us);
        alliedUnits.add(nu);

        return us;
    }

    /**
     * This checks whether an entity has any blown off limbs. If the battlefield
     * was not controlled it marks the limb as destroyed. if the battlefield was
     * controlled it clears the missing status from any equipment.
     *
     * This method should be run the first time an entity is loaded into the tracker,
     * either from the game or from a MUL file.
     * @param en
     * @param controlsField
     */
    private void checkForLostLimbs(Entity en, boolean controlsField) {
        for(int loc = 0; loc < en.locations(); loc++) {
            if(en.isLocationBlownOff(loc) && !controlsField) {
                //sorry dude, we cant find your arm
                en.setLocationBlownOff(loc, false);
                en.setArmor(IArmorState.ARMOR_DESTROYED, loc);
                en.setInternal(IArmorState.ARMOR_DESTROYED, loc);
            }
            //check for mounted and critical slot missingness as well
            for (int i = 0; i < en.getNumberOfCriticals(loc); i++) {
                final CriticalSlot cs = en.getCritical(loc, i);
                if(null == cs || !cs.isEverHittable()) {
                    continue;
                }
                Mounted m = cs.getMount();
                if(cs.isMissing()) {
                    if(controlsField) {
                        cs.setMissing(false);
                        if(null != m) {
                            m.setMissing(false);
                        }
                    } else {
                        if(null != m) {
                            m.setMissing(true);
                        }
                    }
                }
            }
        }
    }

    private List<Person> shuffleCrew(List<Person> source) {
        List<Person> sortedList = new ArrayList<>();
        Random generator = new Random();

        while (source.size() > 0)
        {
            int position = generator.nextInt(source.size());
            sortedList.add(source.get(position));
            source.remove(position);
        }

        return sortedList;
    }

    public void assignKills() {
        final String METHOD_NAME = "assignKills()"; //$NON-NLS-1$

        for(Unit u : units) {
            for(String killed : killCredits.keySet()) {
                if(killCredits.get(killed).equalsIgnoreCase("None")) {
                    continue;
                }
                if(u.getId().toString().equals(killCredits.get(killed))) {
                    for(Person p : u.getActiveCrew()) {
                        PersonStatus status = peopleStatus.get(p.getId());
                        if(null == status) {
                            //this shouldnt happen so report
                            MekHQ.getLogger().log(getClass(), METHOD_NAME, LogLevel.ERROR,
                                    "A null person status was found for person id " + p.getId().toString() //$NON-NLS-1$
                                    + " when trying to assign kills"); //$NON-NLS-1$
                            continue;
                        }
                        status.addKill(new Kill(p.getId(), killed, u.getEntity().getShortNameRaw(), campaign.getCalendar().getTime()));
                    }
                }
            }

        }
    }

    public void checkStatusOfPersonnel() {
        // lets cycle through units and get their crew
        for (Unit u : units) {
            // shuffling the crew ensures that casualties are randomly assigned in multi-crew units
            List<Person> crew = shuffleCrew(u.getActiveCrew());
            Entity en = null;
            UnitStatus unitStatus = unitsStatus.get(u.getId());
            if (null != unitStatus) {
                en = unitStatus.getEntity();
            }
            if (null == en) {
                continue;
            }
            //Handle spacecraft a bit differently
            if ((en instanceof SmallCraft) || (en instanceof Jumpship)) {
                processLargeCraft(u, en, crew, unitStatus);
            } else {
                if (en.getTransportId() != Entity.NONE) {
                    // Check to see if the unit is in a large craft bay, if so, its crew will be processed with the ship,
                    // so ignore it here.
                    UUID trnId = idMap.get(en.getTransportId());
                    if (trnId != null) {
                        Entity transport = unitsStatus.get(trnId).getEntity();
                        if ((transport != null) && transport.isLargeCraft()) {
                            continue;
                        }
                    }
                }
                // check for an ejected entity and if we find one then assign it instead to switch vees
                // over to infantry checks for casualties
                Entity ejected = ejections.get(UUID.fromString(en.getCrew().getExternalIdAsString()));
                // determine total casualties for infantry and large craft
                int casualties = 0;
                int casualtiesAssigned = 0;
                Infantry infantry = null;
                if (en instanceof Infantry) {
                    infantry = (Infantry) en;
                } else if (ejected != null) {
                    infantry = (Infantry) ejected;
                }
                if (infantry != null) {
                    infantry.applyDamage();
                    // If reading from a MUL, the shooting strength is set to Integer.MAX_VALUE if there is no damage.
                    int strength = Math.min(infantry.getShootingStrength(), crew.size());
                    casualties = crew.size() - strength;
                    if (unitStatus.isTotalLoss()) {
                        casualties = crew.size();
                    }
                    // If a tank has already taken hits to the commander or driver, do not assign them again.
                    if (en instanceof Tank) {
                        if (((Tank) en).isDriverHit()) {
                            casualtiesAssigned++;
                        }
                        if (((Tank) en).isCommanderHit()) {
                            casualtiesAssigned++;
                        }
                    }
                }
                //try to find the crew in our pilot and mia vectors
                Crew pilot = pilots.get(u.getCommander().getId());
                boolean missingCrew = false;
                //For multi-crew cockpits, the crew id is the first slot, which is not necessarily the commander
                if (null == pilot) {
                    for (Person p : u.getCrew()) {
                        if (pilots.containsKey(p.getId())) {
                            pilot = pilots.get(p.getId());
                            break;
                        }
                    }
                }
                if (null == pilot) {
                    pilot = mia.get(UUID.fromString(en.getCrew().getExternalIdAsString()));
                    missingCrew = true;
                }
                for (Person p : crew) {
                    PersonStatus status = new PersonStatus(p.getFullName(), u.getEntity().getDisplayName(),
                            p.getHits(), p.getId());
                    status.setMissing(missingCrew);
                    // if the pilot was not found in either the pilot or mia vector
                    // then the unit was devastated and no one ejected, so they should be dead, really dead
                    if (null == pilot) {
                        status.setHits(6);
                        status.setDead(true);
                    }
                    // multi-crewed cockpit; set each crew member separately
                    else if (pilot.getSlotCount() > 1) {
                        for (int slot = 0; slot < pilot.getSlotCount(); slot++) {
                            if (p.getId().toString().equals(pilot.getExternalIdAsString(slot))) {
                                status.setHits(pilot.getHits(slot));
                                break;
                            }
                        }
                    // else if: can't do the following by u.usesSoloPilot because entity may be different if ejected
                    } else if (en instanceof Mech || en instanceof Protomech || en.isFighter()) {
                        status.setHits(pilot.getHits());
                    } else {
                        // we have a multi-crewed Vehicle/Aero/Infantry
                        boolean wounded = false;
                        // tanks need to be handled specially because of the special crits and because
                        // tank destruction should "kill" the crew
                        if (en instanceof Tank) {
                            boolean destroyed = false;
                            for (int loc = 0; loc < en.locations(); loc++) {
                                if (loc == Tank.LOC_TURRET || loc == Tank.LOC_TURRET_2 || loc == Tank.LOC_BODY) {
                                    continue;
                                }
                                if (en.getInternal(loc) <= 0) {
                                    destroyed = true;
                                    break;
                                }
                            }
                            if (destroyed || null == en.getCrew() || en.getCrew().isDead()) {
                                if (Compute.d6(2) >= 7) {
                                    wounded = true;
                                } else {
                                    status.setHits(6);
                                    status.setDead(true);
                                }
                            } else if (((Tank)en).isDriverHit() && u.isDriver(p)) {
                                if (Compute.d6(2) >= 7) {
                                    wounded = true;
                                } else {
                                    status.setHits(6);
                                    status.setDead(true);
                                }
                            } else if (((Tank)en).isCommanderHit() && (u.isCommander(p)
                                    || u.isTechOfficer(p))) {
                                //If there is a command console, the commander hit flag is set on the second such critical,
                                //which means both commanders have been hit.
                                if (Compute.d6(2) >= 7) {
                                    wounded = true;
                                } else {
                                    status.setHits(6);
                                    status.setDead(true);
                                }
                            } else if (((Tank)en).isUsingConsoleCommander() && u.isCommander(p)) {
                                //This flag is set after the first commander hit critical.
                                if (Compute.d6(2) >= 7) {
                                    wounded = true;
                                } else {
                                    status.setHits(6);
                                    status.setDead(true);
                                }
                            }
                        }
                        if (casualtiesAssigned < casualties) {
                            casualtiesAssigned++;
                            if(Compute.d6(2) >= 7) {
                                wounded = true;
                            } else {
                                status.setHits(6);
                                status.setDead(true);
                            }
                        }
                        if (wounded) {
                            int hits = campaign.getCampaignOptions().getMinimumHitsForVees();
                            if (campaign.getCampaignOptions().useAdvancedMedical() || campaign.getCampaignOptions().useRandomHitsForVees()) {
                                int range = 6 - hits;
                                hits = hits + Compute.randomInt(range);
                            }
                            status.setHits(hits);
                        }
                    }
                    status.setXP(campaign.getCampaignOptions().getScenarioXP());
                    status.setDeployed(!en.wasNeverDeployed());
                    peopleStatus.put(p.getId(), status);
                }
            }
        }

        // And now we have potential prisoners that are crewing a unit...
        if(campaign.getCampaignOptions().capturePrisoners()) {
            processPrisonerCapture(potentialSalvage);
            processPrisonerCapture(devastatedEnemyUnits);
        }
    }

    /**
     * Helper function that handles crew and passengers ejected from a large spacecraft,
     * which may be scattered about on numerous other entities
     * @param ship The large craft unit we're currently processing
     * @param en The entity associated with the unit Ship
     * @param personnel The list of persons assigned to the ship as crew and marines
     * @param unitStatus The post-battle status of en
     */
    private void processLargeCraft(Unit ship, Entity en, List<Person> personnel, UnitStatus unitStatus) {
        final String METHOD_NAME = "processLargeCraft(Unit,Entity,List<Person>,UnitStatus)"; //$NON-NLS-1$
        //The entity must be an Aero for us to get here
        Aero aero = (Aero) en;
        //Find out if this large craft ejected or was in the process of ejecting,
        // and if so what entities are carrying the personnel
        int rescuedCrew = 0;
        int rescuedPassengers = 0;
        if (en.getCrew().isEjected() || aero.isEjecting()) {
            for (String id : aero.getEscapeCraft()) {
                Entity e = entities.get(UUID.fromString(id));
                // Invalid entity?
                if (e == null) {
                    MekHQ.getLogger().log(getClass(), METHOD_NAME, LogLevel.ERROR,
                            "Null entity reference in:" + aero.getDisplayName() + "getEscapeCraft()");
                    continue;
                }
                //If the escape craft was destroyed in combat, skip it
                if (e.isDestroyed() || e.isDoomed()) {
                    continue;
                }
                //Now let's see how many passengers and crew we picked up
                if (e instanceof SmallCraft) {
                    SmallCraft craft = (SmallCraft) e;
                    if (craft.getPassengers().get(en.getExternalIdAsString()) != null) {
                        rescuedPassengers += craft.getPassengers().get(en.getExternalIdAsString());
                    }
                    if (craft.getNOtherCrew().get(en.getExternalIdAsString()) != null) {
                        rescuedCrew += craft.getNOtherCrew().get(en.getExternalIdAsString());
                    }
                } else if (e instanceof EjectedCrew) {
                    EjectedCrew crew = (EjectedCrew) e;
                    if (crew.getPassengers().get(en.getExternalIdAsString()) != null) {
                        rescuedPassengers += crew.getPassengers().get(en.getExternalIdAsString());
                    }
                    if (crew.getNOtherCrew().get(en.getExternalIdAsString()) != null) {
                        rescuedCrew += crew.getNOtherCrew().get(en.getExternalIdAsString());
                    }
                }
            }
        }
        //Check crewed aeros for existing hits since they could be flying without full crews
        int casualties = 0;
        int casualtiesAssigned = 0;
        int existingHits = 0;
        int currentHits = 0;
        if (null != ship.getEntity().getCrew()) {
            existingHits = ship.getEntity().getCrew().getHits();
        }
        if (null != en.getCrew()) {
            currentHits = en.getCrew().getHits();
        }
        if (en.isDestroyed()) {
            currentHits = 6;
        }
        int newHits = Math.max(0, currentHits - existingHits);
        casualties = (int) Math.ceil(Compute.getFullCrewSize(en) * (newHits / 6.0));
        //Now reduce the casualties if some "hits" were caused by ejection
        casualties = Math.max(0, casualties - rescuedCrew);

        //And assign the casualties and experience amongst the crew and marines
        for (Person p : personnel) {
            PersonStatus status = new PersonStatus(p.getFullName(), ship.getEntity().getDisplayName(),
                    p.getHits(), p.getId());
            boolean wounded = false;
            if (casualtiesAssigned < casualties) {
                casualtiesAssigned++;
                if (Compute.d6(2) >= 7) {
                    wounded = true;
                } else {
                    status.setHits(6);
                    status.setDead(true);
                }
            }
            if (wounded) {
                int hits = campaign.getCampaignOptions().getMinimumHitsForVees();
                if (campaign.getCampaignOptions().useAdvancedMedical() || campaign.getCampaignOptions().useRandomHitsForVees()) {
                    int range = 6 - hits;
                    hits = hits + Compute.randomInt(range);
                }
                status.setHits(hits);
            }
            status.setXP(campaign.getCampaignOptions().getScenarioXP());
            status.setDeployed(!en.wasNeverDeployed());
            peopleStatus.put(p.getId(), status);
        }

        //Now, did the passengers take any hits?
        //We'll assume that if units in transport bays were hit, their crews and techs might also have been
        Set<PersonStatus> allPassengersStatus = new HashSet<>(); //Use this to keep track of ejected passengers for the next step
        List<Entity> cargo = bayLoadedEntities.get(UUID.fromString(en.getExternalIdAsString()));
        if (cargo != null) {
            for (Entity e : cargo) {
                //Match the still-loaded cargo entity with its unit so we can get the crew
                Unit u = campaign.getUnit(UUID.fromString(e.getExternalIdAsString()));
                if (u != null) {
                    List<Person> cargoCrew = u.getActiveCrew();
                    cargoCrew.add(u.getTech());
                    cargoCrew = shuffleCrew(cargoCrew);
                    for (Person p : cargoCrew) {
                        PersonStatus status = new PersonStatus(p.getFullName(), u.getEntity().getDisplayName(),
                                p.getHits(), p.getId());
                        boolean wounded = false;
                        //The lore says bay crews have pressurized sleeping alcoves in the corners of each bay
                        //Let's assume people are injured on an 8+ if the unit is destroyed, same as a critical hit chance
                        if (e.isDestroyed() && Compute.d6(2) >= 8) {
                            //As with crewmembers, on a 7+ they're only wounded
                            if (Compute.d6(2) >= 7) {
                                wounded = true;
                            } else {
                                status.setHits(6);
                                status.setDead(true);
                            }
                            if (wounded) {
                                int hits = campaign.getCampaignOptions().getMinimumHitsForVees();
                                if (campaign.getCampaignOptions().useAdvancedMedical() || campaign.getCampaignOptions().useRandomHitsForVees()) {
                                    int range = 6 - hits;
                                    hits = hits + Compute.randomInt(range);
                                }
                                status.setHits(hits);
                            }
                        }
                        //Go ahead and add everyone to this master list, even if they're killed/wounded above.
                        //It's normal to have some casualties in the lifeboats...
                        allPassengersStatus.add(status);
                    }
                }
            }
        }
        //Now let's account for any passengers aboard escape craft
        //If the host ship ended the game mid-ejection but remains undestroyed, add its remaining passengers
        if (aero.isEjecting() || aero.getCrew().isEjected()) {
            if (aero.isEjecting() && !aero.isDestroyed()) {
                rescuedPassengers = allPassengersStatus.size();
            }
            //Convert the set to a list so we can pick a random value by index...
            List<PersonStatus> allPassengersStatusList = new ArrayList<>();
            for (PersonStatus s : allPassengersStatus) {
                allPassengersStatusList.add(s);
            }

            //Let's go through and handle the list
            while (rescuedPassengers > 0) {
                if (allPassengersStatus.isEmpty()) {
                    //Could happen on ships with passenger quarters where numbers exceed those associated
                    //with transported units, or ships that are just empty
                    break;
                }
                PersonStatus s = allPassengersStatusList.remove(Compute.randomInt(allPassengersStatusList.size()));
                UUID pid = s.getId();
                peopleStatus.put(pid, s);
                rescuedPassengers --;
            }
            //Everyone who didn't make it to an escape craft dies
            for (PersonStatus s : allPassengersStatusList) {
                s.setHits(6);
                s.setDead(true);
                peopleStatus.put(s.getId(), s);
            }
        } else {
            //No ejection is involved, just add everyone to our master peopleStatus table
            for (PersonStatus s : allPassengersStatus) {
                peopleStatus.put(s.getId(), s);
            }
        }
    }

    /**
     * Helper function that contains the logic for processing prisoner capture.
     * Copy and pasted from checkStatusOfPersonnel, so the internal logic is kind of opaque.
     * @param unitsToProcess The list of TestUnit entities to process. Note that
     *                  in order to be processed, a unit must be in the salvageStatus hashtable.
     */
    private void processPrisonerCapture(List<TestUnit> unitsToProcess) {

        Mission currentMission = campaign.getMission(scenario.getMissionId());
        String enemyCode;
        if (currentMission instanceof AtBContract) {
            enemyCode = ((AtBContract) currentMission).getEnemyCode();
        } else {
            enemyCode = "IND";
        }

        for(Unit u : unitsToProcess) {
            if (null == u) {
                continue; // Shouldn't happen... but well... ya know
            }
            Entity en = null;
            UnitStatus ustatus = salvageStatus.get(u.getId());
            if(null != ustatus) {
                en = ustatus.getEntity();
            }
            if(null == en) {
                continue;
            }
            //check for an ejected entity and if we find one then assign it instead to switch vees
            //over to infantry checks for casualties
            Entity ejected = null;
            if (!en.getCrew().getExternalIdAsString().equals("-1")) {
                ejected = enemyEjections.get(UUID.fromString(en.getCrew().getExternalIdAsString()));
            }
            if(null != ejected) {
                en = ejected;
            }
            //check if this ejection was picked up by a player's unit
            boolean pickedUp = en instanceof MechWarrior
                    && !((MechWarrior)en).getPickedUpByExternalIdAsString().equals("-1")
                    && null != unitsStatus.get(UUID.fromString(((MechWarrior)en).getPickedUpByExternalIdAsString()));
            // If this option is turned on and the player controls the battlefield,
            // assume that all ejected warriors have been picked up
            if (campaign.getGameOptions().booleanOption(OptionsConstants.ADVGRNDMOV_EJECTED_PILOTS_FLEE)) {
                pickedUp = true;
            }
            //if the crew ejected from this unit, then skip it because we should find them elsewhere
            //if they are alive
            if(!(en instanceof EjectedCrew)
                    && null != en.getCrew()
                    && (en.getCrew().isEjected() && !campaign.getGameOptions().booleanOption(OptionsConstants.ADVGRNDMOV_EJECTED_PILOTS_FLEE))) {
                continue;
            }
            //shuffling the crew ensures that casualties are randomly assigned in multi-crew units
            List<Person> crew = Utilities.genRandomCrewWithCombinedSkill(campaign, u, enemyCode).values().stream().flatMap(Collection::stream).collect(Collectors.toList());
            crew = shuffleCrew(crew);

            //For vees we may need to know the commander or driver, which aren't assigned for TestUnit.
            Person commander = null;
            Person driver = null;
            Person console = null;
            if (en instanceof Tank) {
                //Prefer gunner over driver, as in Unit::getCommander
                for (Person p : crew) {
                    if (p.getPrimaryRole() == Person.T_VEE_GUNNER) {
                        commander = p;
                    } else if (p.getPrimaryRole() == Person.T_GVEE_DRIVER
                            || p.getPrimaryRole() == Person.T_VTOL_PILOT
                            || p.getPrimaryRole() == Person.T_NVEE_DRIVER) {
                        driver = p;
                    }
                }
                if (en.hasWorkingMisc(MiscType.F_COMMAND_CONSOLE)) {
                    for (Person p : crew) {
                        if (!p.equals(commander) && !p.equals(driver)) {
                            console = p;
                            break;
                        }
                    }
                }
            }
            if (commander == null && crew.size() > 0) {
                commander = crew.get(0);
            }

            int casualties = 0;
            int casualtiesAssigned = 0;
            if (en instanceof Infantry) {
                en.applyDamage();
                int strength = ((Infantry)en).getShootingStrength();
                casualties = crew.size() - strength;
            }
            if (en instanceof Aero && !u.usesSoloPilot()) {
                //need to check for existing hits because you can fly aeros with less than full
                //crew
                int existingHits = 0;
                int currentHits = 0;
                if (null != u.getEntity().getCrew()) {
                    existingHits = u.getEntity().getCrew().getHits();
                }
                if (null != en.getCrew()) {
                    currentHits = en.getCrew().getHits();
                }
                int newHits = (int) Math.max(0, currentHits - existingHits);
                casualties = (int) Math.ceil(Compute.getFullCrewSize(en) * (newHits/6.0));
            }
            for(Person p : crew) {
                // Give them a UUID. We won't actually use this for the campaign, but to
                //identify them in the prisonerStatus hash
                UUID id = p.getId();
                if (null == id) {
                    id = UUID.randomUUID();
                    p.setId(id);
                }
                PrisonerStatus status = new PrisonerStatus(p.getFullName(), u.getEntity().getDisplayName(), p);
                if (en instanceof Mech
                        || en instanceof Protomech
                        || en.isFighter()
                        || en instanceof MechWarrior) {
                    Crew pilot = en.getCrew();
                    if(null == pilot) {
                        continue;
                    }
                    int slot = 0;
                    //For multi-person cockpits the person id has been set to match the crew slot
                    for (int pos = 0; pos < pilot.getSlotCount(); pos++) {
                        if (p.getId().toString().equals(pilot.getExternalIdAsString(pos))) {
                            slot = pos;
                            break;
                        }
                    }
                    status.setHits(pilot.getHits(slot));
                } else {
                    //we have a multi-crewed vee
                    boolean wounded = false;
                    if (en instanceof Tank) {
                        boolean destroyed = false;
                        for (int loc = 0; loc < en.locations(); loc++) {
                            if (loc == Tank.LOC_TURRET || loc == Tank.LOC_TURRET_2 || loc == Tank.LOC_BODY) {
                                continue;
                            }
                            if (en.getInternal(loc) <= 0) {
                                destroyed = true;
                                break;
                            }
                        }
                        if (destroyed || (null == en.getCrew()) || en.getCrew().isDead()) {
                            if (Compute.d6(2) >= 7) {
                                wounded = true;
                            } else {
                                status.setHits(6);
                            }
                        } else if (((Tank) en).isDriverHit() && (driver != null)
                                && p.getId().equals(driver.getId())) {
                            if (Compute.d6(2) >= 7) {
                                wounded = true;
                            } else {
                                status.setHits(6);
                                status.setDead(true);
                            }
                        } else if (((Tank) en).isCommanderHit()
                                && (((commander != null) && p.getId().equals(commander.getId()))
                                        || ((console != null) && p.getId().equals(console.getId())))) {
                            //If there is a command console, the commander hit flag does not
                            //get set until after the second such critical, which means that
                            //both commanders have been hit.
                            if (Compute.d6(2) >= 7) {
                                wounded = true;
                            } else {
                                status.setHits(6);
                                status.setDead(true);
                            }
                        } else if (((Tank) en).isUsingConsoleCommander() && (commander != null)
                                && commander.getId().equals(p.getId())) {
                            //If this flag is set we are using a command console and have already
                            //taken one commander hit critical, which takes out the primary commander.
                            if(Compute.d6(2) >= 7) {
                                wounded = true;
                            } else {
                                status.setHits(6);
                                status.setDead(true);
                            }
                        }
                    }
                    else if(en instanceof Infantry || en instanceof Aero) {
                        if(casualtiesAssigned < casualties) {
                            casualtiesAssigned++;
                            if(Compute.d6(2) >= 7) {
                                wounded = true;
                            } else {
                                status.setHits(6);
                                status.setDead(true);
                            }
                        }
                    }
                    if(wounded) {
                        int hits = campaign.getCampaignOptions().getMinimumHitsForVees();
                        if (campaign.getCampaignOptions().useAdvancedMedical() || campaign.getCampaignOptions().useRandomHitsForVees()) {
                            int range = 6 - hits;
                            hits = hits + Compute.randomInt(range);
                        }
                        status.setHits(hits);
                    }
                }
                status.setCaptured(Utilities.isLikelyCapture(en) || pickedUp);
                status.setXP(campaign.getCampaignOptions().getScenarioXP());
                prisonerStatus.put(id, status);
            }
        }
    }

    private void loadUnitsAndPilots(File unitFile) throws IOException {
        final String METHOD_NAME = "loadUnitsAndPilots(File)"; //$NON-NLS-1$

        if (unitFile != null) {
            // I need to get the parser myself, because I want to pull both
            // entities and pilots from it
            // Create an empty parser.
            MULParser parser = new MULParser();

            // Open up the file.
            try (InputStream listStream = new FileInputStream(unitFile)) {
                // Read a Vector from the file.
                parser.parse(listStream);
            } catch (Exception e) {
                MekHQ.getLogger().error(getClass(), "loadUnitsAndPilots", e);
            }

            // Was there any error in parsing?
            if (parser.hasWarningMessage()) {
                MekHQ.getLogger().log(getClass(), METHOD_NAME, LogLevel.WARNING,
                        parser.getWarningMessage());
            }

            killCredits = parser.getKills();

            //Map everyone's ID to External Id
            for (Entity e : parser.getEntities()) {
                idMap.put(e.getId(), UUID.fromString(e.getExternalIdAsString()));
            }
            for (Entity e : parser.getSalvage()) {
                idMap.put(e.getId(), UUID.fromString(e.getExternalIdAsString()));
            }
            for (Entity e : parser.getRetreated()) {
                idMap.put(e.getId(), UUID.fromString(e.getExternalIdAsString()));
            }

            //If any units ended the game with others loaded in its bays, map those out
            for (Entity e : parser.getEntities()) {
                if (!e.getBayLoadedUnitIds().isEmpty()) {
                    List<Entity> cargo = new ArrayList<>();
                    for (int id : e.getBayLoadedUnitIds()) {
                        UUID extId = idMap.get(id);
                        if (extId != null) {
                            cargo.add(entities.get(extId));
                        }
                    }
                    bayLoadedEntities.put(UUID.fromString(e.getExternalIdAsString()), cargo);
                }
            }

            for (Entity e : parser.getSurvivors()) {
                entities.put(UUID.fromString(e.getExternalIdAsString()), e);
                checkForLostLimbs(e, control);
                if(!e.getExternalIdAsString().equals("-1")) {
                    UnitStatus status = unitsStatus.get(UUID.fromString(e.getExternalIdAsString()));
                    if (null == status && scenario instanceof AtBScenario && !(e instanceof EjectedCrew)) {
                        status = processAlliedUnit(e);
                    }

                    if(null != status) {
                        boolean lost = (!e.canEscape() && !control) || e.getRemovalCondition() == IEntityRemovalConditions.REMOVE_DEVASTATED;
                        status.assignFoundEntity(e, lost);
                    }
                }

                if(null != e.getCrew()) {
                    if(!e.getCrew().getExternalIdAsString().equals("-1")) {
                        if(!e.getCrew().isEjected() || e instanceof EjectedCrew) {
                            pilots.put(UUID.fromString(e.getCrew().getExternalIdAsString()), e.getCrew());
                        }
                        if(e instanceof EjectedCrew) {
                            ejections.put(UUID.fromString(e.getCrew().getExternalIdAsString()), (EjectedCrew)e);
                        }

                    }
                }
            }

            for (Entity e : parser.getAllies()) {
                entities.put(UUID.fromString(e.getExternalIdAsString()), e);
                checkForLostLimbs(e, control);
                if(!e.getExternalIdAsString().equals("-1")) {
                    UnitStatus status = unitsStatus.get(UUID.fromString(e.getExternalIdAsString()));
                    if (null == status && scenario instanceof AtBScenario && !(e instanceof EjectedCrew)) {
                        status = processAlliedUnit(e);
                    }

                    if(null != status) {
                        boolean lost = (!e.canEscape() && !control) || e.getRemovalCondition() == IEntityRemovalConditions.REMOVE_DEVASTATED;
                        status.assignFoundEntity(e, lost);
                    }
                }
                if(null != e.getCrew()) {
                    if(!e.getCrew().getExternalIdAsString().equals("-1")) {
                        if(!e.getCrew().isEjected() || e instanceof EjectedCrew) {
                            pilots.put(UUID.fromString(e.getCrew().getExternalIdAsString()), e.getCrew());
                        }
                        if(e instanceof EjectedCrew) {
                            ejections.put(UUID.fromString(e.getCrew().getExternalIdAsString()), (EjectedCrew)e);
                        }
                    }
                }
            }

            // Utterly destroyed entities
            for (Entity e : parser.getDevastated()) {
                entities.put(UUID.fromString(e.getExternalIdAsString()), e);
                UnitStatus status = null;
                if(!e.getExternalIdAsString().equals("-1")) {
                    status = unitsStatus.get(UUID.fromString(e.getExternalIdAsString()));
                }
                if(null != status) {
                    status.assignFoundEntity(e, true);
                } else {
                    // completely destroyed units (such as from an ammo explosion) need to be
                    // kept track of, as mechwarriors may eject from them, etc.
                    TestUnit nu = generateNewTestUnit(e);
                    UnitStatus us = new UnitStatus(nu);
                    salvageStatus.put(nu.getId(), us);
                    devastatedEnemyUnits.add(nu);
                }
            }

            for(Entity e : parser.getSalvage()) {
                entities.put(UUID.fromString(e.getExternalIdAsString()), e);
                checkForLostLimbs(e, control);
                UnitStatus status = null;
                if(!e.getExternalIdAsString().equals("-1") && e.isSalvage()) {
                    // Check to see if this is a friendly deployed unit with a unit ID in the campaign
                    status = unitsStatus.get(UUID.fromString(e.getExternalIdAsString()));
                }
                //If a unit in a bay was destroyed, add it. We still need to deal with the crew
                if (e.getTransportId() != Entity.NONE) {
                    UUID trnId = idMap.get(e.getTransportId());
                    List<Entity> cargo;
                    if (bayLoadedEntities.containsKey(trnId)) {
                        cargo = bayLoadedEntities.get(trnId);
                    } else {
                        cargo = new ArrayList<>();
                    }
                    e.setDestroyed(true);
                    cargo.add(e);
                    bayLoadedEntities.put(trnId, cargo);
                }
                if(null != status) {
                    status.assignFoundEntity(e, !control);
                    if(null != e.getCrew()) {
                        if(!e.getCrew().getExternalIdAsString().equals("-1")) {
                            if(e instanceof EjectedCrew) {
                                ejections.put(UUID.fromString(e.getCrew().getExternalIdAsString()), (EjectedCrew)e);
                            }
                            if(!e.getCrew().isEjected() || e instanceof EjectedCrew) {
                                if(control) {
                                    pilots.put(UUID.fromString(e.getCrew().getExternalIdAsString()), e.getCrew());
                                } else {
                                    mia.put(UUID.fromString(e.getCrew().getExternalIdAsString()), e.getCrew());
                                }
                            }
                        }
                    }
                } else {
                    // Enemy crew/pilot entity is actually in the salvage list
                    if(e instanceof EjectedCrew && null != e.getCrew() && !e.getCrew().getExternalIdAsString().equals("-1")) {
                        enemyEjections.put(UUID.fromString(e.getCrew().getExternalIdAsString()), (EjectedCrew)e);
                        continue;
                    }
                    if(control) {
                        TestUnit nu = generateNewTestUnit(e);
                        UnitStatus us = new UnitStatus(nu);
                        us.setTotalLoss(false);
                        salvageStatus.put(nu.getId(), us);
                        potentialSalvage.add(nu);
                    }
                }
            }

            for(Entity e : parser.getRetreated()) {
                if(!e.getExternalIdAsString().equals("-1")) {
                    UnitStatus status = unitsStatus.get(UUID.fromString(e.getExternalIdAsString()));
                    if (null == status && scenario instanceof AtBScenario) {
                        status = processAlliedUnit(e);
                    }

                    if(null != status) {
                        status.assignFoundEntity(e, false);
                    }
                    if (!e.getBayLoadedUnitIds().isEmpty()) {
                        List<Entity> cargo = new ArrayList<>();
                        for (int id : e.getBayLoadedUnitIds()) {
                            UUID extId = idMap.get(id);
                            if (extId != null) {
                                cargo.add(entities.get(extId));
                            }
                        }
                        bayLoadedEntities.put(UUID.fromString(e.getExternalIdAsString()), cargo);
                    }
                }
                if(null != e.getCrew()) {
                    if(!e.getCrew().getExternalIdAsString().equals("-1")) {
                        pilots.put(UUID.fromString(e.getCrew().getExternalIdAsString()), e.getCrew());
                        if(e instanceof EjectedCrew) {
                            ejections.put(UUID.fromString(e.getCrew().getExternalIdAsString()), (EjectedCrew)e);
                        }
                    }
                }

                entities.put(UUID.fromString(e.getExternalIdAsString()), e);
            }
        }
    }

    /**
     * When resolving the battle manually without a resolution file (such as MekHQ + tabletop),
     * set initial status of units and crew as pre-battle.
     */
    private void initUnitsAndPilotsWithoutBattle() {
    	for (Unit u : units) {
    		UnitStatus status = unitsStatus.get(u.getId());
    		status.assignFoundEntity(u.getEntity(), false);
    		u.getEntity().setDeployed(true);
    		Crew crew = u.getEntity().getCrew();
            if(null != crew && !crew.getExternalIdAsString().equals("-1")) {
            	pilots.put(UUID.fromString(crew.getExternalIdAsString()), crew);
            }

            entities.put(UUID.fromString(u.getEntity().getExternalIdAsString()), u.getEntity());
    	}

    	// if it's an AtB scenario, load up all the bot units into the entities collection
    	// for objective processing
    	if(scenario instanceof AtBScenario) {
    	    AtBScenario atbScenario = (AtBScenario) scenario;
    	    for(Entity e : atbScenario.getAlliesPlayer()) {
    	        entities.put(UUID.fromString(e.getExternalIdAsString()), e);
    	    }

    	    for(int x = 0; x < atbScenario.getNumBots(); x++) {
    	        BotForce botForce = atbScenario.getBotForce(x);

    	        for(Entity e : botForce.getEntityList()) {
                    entities.put(UUID.fromString(e.getExternalIdAsString()), e);
                }
    	    }
    	}
    }

    public ArrayList<TestUnit> getAlliedUnits() {
        return alliedUnits;
    }

    public ArrayList<TestUnit> getPotentialSalvage() {
        return potentialSalvage;
    }

    public ArrayList<TestUnit> getActualSalvage() {
        return actualSalvage;
    }

    public void salvageUnit(int i) {
        TestUnit salvageUnit = potentialSalvage.get(i);
        actualSalvage.add(salvageUnit);
    }

    public void dontSalvageUnit(int i) {
        leftoverSalvage.add(potentialSalvage.get(i));
    }

    public void setContractBreaches(int i) {
        contractBreaches = i;
    }

    public void setBonusRolls(int i) {
        bonusRolls = i;
    }

    public Campaign getCampaign() {
        return campaign;
    }

    public Scenario getScenario() {
        return scenario;
    }

    public Mission getMission() {
        return campaign.getMission(scenario.getMissionId());
    }

    public Hashtable<String, String> getKillCredits() {
        return killCredits;
    }

    public ArrayList<Unit> getUnits() {
        return units;
    }

    public void resolveScenario(int resolution, String report) {
        //lets start by generating a stub file for our records
        scenario.generateStub(campaign);

        //ok lets do the whole enchilada and go ahead and update campaign

        //first figure out if we need any battle loss comp
        double blc = 0;
        Mission m = campaign.getMission(scenario.getMissionId());
        if (m instanceof Contract) {
            blc = ((Contract)m).getBattleLossComp()/100.0;
        }

        //now lets update personnel
        for (UUID pid : peopleStatus.keySet()) {
            Person person = campaign.getPerson(pid);
            PersonStatus status = peopleStatus.get(pid);
            if (null == person || null == status) {
                continue;
            }

            MekHQ.triggerEvent(new PersonBattleFinishedEvent(person, status));
            if (status.getHits() > person.getHits()) {
                person.setHits(status.getHits());
            }
            if (status.wasDeployed()) {
                person.setXp(person.getXp() + status.getXP());
                ServiceLogger.participatedInMission(person, campaign.getDate(), scenario.getName(), m.getName());
            }
            for (Kill k : status.getKills()) {
                campaign.addKill(k);
            }
            if (status.isMissing()) {
                campaign.changeStatus(person, PersonnelStatus.MIA);
            } else if (status.isDead()) {
                campaign.changeStatus(person, PersonnelStatus.KIA);
                if (campaign.getCampaignOptions().getUseAtB() && m instanceof AtBContract) {
                    campaign.getRetirementDefectionTracker().removeFromCampaign(person,
                            true, campaign.getCampaignOptions().getUseShareSystem()
                                    ? person.getNumShares(campaign.getCampaignOptions().getSharesForAll())
                                    : 0,
                            campaign, (AtBContract) m);
                }
            }
            if (campaign.getCampaignOptions().useAdvancedMedical()) {
                person.diagnose(status.getHits());
            }
            if (status.toRemove()) {
                campaign.removePerson(pid, false);
            }
        }
        // update prisoners
        for (UUID pid : prisonerStatus.keySet()) {
            PrisonerStatus status = prisonerStatus.get(pid);
            Person person = status.getPerson();
            if (null == person) {
                continue;
            }
            MekHQ.triggerEvent(new PersonBattleFinishedEvent(person, status));
            if (status.isDead()) {
                continue;
            }
            if (status.isCaptured()) {
                getCampaign().recruitPerson(person, true, false, false, true);
                if (getCampaign().getCampaignOptions().getUseAtB() &&
                        getCampaign().getCampaignOptions().getUseAtBCapture() &&
                        m instanceof AtBContract && status.isCaptured()) {
                    if (Compute.d6(2) >= 10 + ((AtBContract)m).getEnemySkill() - getCampaign().getUnitRatingMod()) {
                        getCampaign().addReport(String.format(
                            "You have convinced %s to defect.", person.getHyperlinkedName())); //$NON-NLS-1$
                        person.setWillingToDefect(true);
                    }
                }
            } else {
                continue;
            }
            person.setXp(person.getXp() + status.getXP());
            if (status.getHits() > person.getHits()) {
                person.setHits(status.getHits());
            }

            ServiceLogger.participatedInMission(person, campaign.getDate(), scenario.getName(), m.getName());

            for (Kill k : status.getKills()) {
                campaign.addKill(k);
            }

            if (status.isMissing()) {
                campaign.changeStatus(person, PersonnelStatus.MIA);
            } else if (status.isDead()) {
                campaign.changeStatus(person, PersonnelStatus.KIA);
                if (campaign.getCampaignOptions().getUseAtB() && (m instanceof AtBContract)) {
                    campaign.getRetirementDefectionTracker().removeFromCampaign(person,
                            true, campaign.getCampaignOptions().getUseShareSystem()
                                    ? person.getNumShares(campaign.getCampaignOptions().getSharesForAll())
                                    : 0,
                            campaign, (AtBContract)m);
                }
            }
            if (campaign.getCampaignOptions().useAdvancedMedical()) {
                person.diagnose(status.getHits());
            }
        }

        //now lets update all units
        for(Unit unit : units) {
            UnitStatus ustatus = unitsStatus.get(unit.getId());
            if(null == ustatus) {
                //shouldn't happen
                continue;
            }
            Entity en = ustatus.getEntity();
            Money unitValue = unit.getBuyCost();
            if(campaign.getCampaignOptions().useBLCSaleValue()) {
                unitValue = unit.getSellValue();
            }
            if(ustatus.isTotalLoss()) {
                //missing unit
                if(blc > 0) {
                    Money value = unitValue.multipliedBy(blc);
                    campaign.getFinances().credit(value, Transaction.C_BLC, "Battle loss compensation for " + unit.getName(), campaign.getCalendar().getTime());
                    campaign.addReport(value.toAmountAndSymbolString() + " in battle loss compensation for " + unit.getName() + " has been credited to your account.");
                }
                campaign.removeUnit(unit.getId());
            } else {
                Money currentValue = unit.getValueOfAllMissingParts();
                campaign.clearGameData(en);
                // FIXME: Need to implement a "fuel" part just like the "armor" part
                if (en.isAero()) {
                    ((IAero)en).setFuelTonnage(((IAero)ustatus.getBaseEntity()).getFuelTonnage());
                }
                unit.setEntity(en);
                if (en.usesWeaponBays()) {
                    unit.adjustLargeCraftAmmo();
                }
                unit.runDiagnostic(true);
                unit.resetPilotAndEntity();
                if(!unit.isRepairable()) {
                    unit.setSalvage(true);
                }
                campaign.addReport(unit.getHyperlinkedName() + " has been recovered.");
                //check for BLC
                Money newValue = unit.getValueOfAllMissingParts();
                Money blcValue = newValue.minus(currentValue);
                Money repairBLC = Money.zero();
                String blcString = "battle loss compensation (parts) for " + unit.getName();
                if(!unit.isRepairable()) {
                    //if the unit is not repairable, you should get BLC for it but we should subtract
                    //the value of salvageable parts
                    blcValue = unitValue.minus(unit.getSellValue());
                    blcString = "battle loss compensation for " + unit.getName();
                }
                if (campaign.getCampaignOptions().payForRepairs()) {
                    for(Part p : unit.getParts()) {
                        if (p.needsFixing() && !(p instanceof Armor)) {
                            repairBLC = repairBLC.plus(p.getStickerPrice().multipliedBy(0.2));
                        }
                    }
                }
                blcValue = blcValue.plus(repairBLC);
                if(blc > 0 && blcValue.isPositive()) {
                    Money finalValue = blcValue.multipliedBy(blc);
                    campaign.getFinances().credit(
                            finalValue,
                            Transaction.C_BLC,
                            blcString.substring(0, 1).toUpperCase() + blcString.substring(1),
                            campaign.getCalendar().getTime());
                    campaign.addReport( finalValue.toAmountAndSymbolString() + " in " + blcString + " has been credited to your account.");
                }
            }
        }

        //now lets take care of salvage
        for(TestUnit salvageUnit : actualSalvage) {
            UnitStatus salstatus = new UnitStatus(salvageUnit);
            // FIXME: Need to implement a "fuel" part just like the "armor" part
            if (salvageUnit.getEntity() instanceof Aero) {
                ((Aero)salvageUnit.getEntity()).setFuelTonnage(((Aero)salstatus.getBaseEntity()).getFuelTonnage());
            }
            campaign.clearGameData(salvageUnit.getEntity());
            campaign.addTestUnit(salvageUnit);
            //if this is a contract, add to the salvaged value
            if(getMission() instanceof Contract) {
                ((Contract)getMission()).addSalvageByUnit(salvageUnit.getSellValue());
            }
        }
        if(getMission() instanceof Contract) {
            Money value = Money.zero();
            for(Unit salvageUnit : leftoverSalvage) {
                value = value.plus(salvageUnit.getSellValue());
            }
            if(((Contract)getMission()).isSalvageExchange()) {
                value = value.multipliedBy(((Contract)getMission()).getSalvagePct()).dividedBy(100);
                campaign.getFinances().credit(value, Transaction.C_SALVAGE, "salvage exchange for " + scenario.getName(),  campaign.getCalendar().getTime());
                campaign.addReport(value.toAmountAndSymbolString() + " have been credited to your account for salvage exchange.");
            } else {
                ((Contract)getMission()).addSalvageByEmployer(value);
            }
        }

        if (campaign.getCampaignOptions().getUseAtB() && getMission() instanceof AtBContract) {
            int unitRatingMod = campaign.getUnitRatingMod();
            for (Unit unit : units) {
                unit.setSite(((AtBContract)getMission()).getRepairLocation(unitRatingMod));
            }
            for (Unit unit : actualSalvage) {
                unit.setSite(((AtBContract)getMission()).getRepairLocation(unitRatingMod));
            }
        }

        for(Loot loot : actualLoot) {
            loot.get(campaign, scenario);
        }

        scenario.setStatus(resolution);
        scenario.setReport(report);
        scenario.clearAllForcesAndPersonnel(campaign);
        //lets reset the network ids from the c3UUIDs
        campaign.reloadGameEntities();
        campaign.refreshNetworks();
        scenario.setDate(campaign.getCalendar().getTime());
        client = null;
    }

    public ArrayList<Person> getMissingPersonnel() {
        ArrayList<Person> mia = new ArrayList<>();
        for(UUID pid : peopleStatus.keySet()) {
            PersonStatus status = peopleStatus.get(pid);
            if(status.isMissing()) {
                Person p = campaign.getPerson(pid);
                if(null != p) {
                    mia.add(p);
                }
            }
        }
        return mia;
    }

    public ArrayList<Person> getDeadPersonnel() {
        ArrayList<Person> kia = new ArrayList<>();
        for(UUID pid : peopleStatus.keySet()) {
            PersonStatus status = peopleStatus.get(pid);
            if(status.isDead()) {
                Person p = campaign.getPerson(pid);
                if(null != p) {
                    kia.add(p);
                }
            }
        }
        return kia;
    }

    public ArrayList<Person> getRecoveredPersonnel() {
        ArrayList<Person> recovered = new ArrayList<>();
        for(UUID pid : peopleStatus.keySet()) {
            PersonStatus status = peopleStatus.get(pid);
            if(!status.isDead() && !status.isMissing()) {
                Person p = campaign.getPerson(pid);
                if(null != p) {
                    recovered.add(p);
                }
            }
        }
        return recovered;
    }

    public Hashtable<UUID, PersonStatus> getPeopleStatus() {
        return peopleStatus;
    }

    public Hashtable<UUID, PrisonerStatus> getPrisonerStatus() {
        return prisonerStatus;
    }

    public Hashtable<UUID, UnitStatus> getUnitsStatus() {
        return unitsStatus;
    }

    public Hashtable<UUID, UnitStatus> getSalvageStatus() {
        return salvageStatus;
    }

    public Map<UUID, Entity> getAllInvolvedUnits() {
        return entities;
    }

    public ArrayList<Loot> getPotentialLoot() {
        return potentialLoot;
    }

    public void addLoot(Loot loot) {
        actualLoot.add(loot);
    }

    public ArrayList<PersonStatus> getSortedPeople() {
        //put all the PersonStatuses in an ArrayList and sort by the unit name
        ArrayList<PersonStatus> toReturn = new ArrayList<>();
        for(UUID id : getPeopleStatus().keySet()) {
            PersonStatus status = peopleStatus.get(id);
            if(null != status) {
                toReturn.add(status);
            }
        }
        //now sort
        Collections.sort(toReturn);
        return toReturn;
    }

    public ArrayList<PrisonerStatus> getSortedPrisoners() {
        //put all the PersonStatuses in an ArrayList and sort by the unit name
        ArrayList<PrisonerStatus> toReturn = new ArrayList<>();
        for(UUID id : getPrisonerStatus().keySet()) {
            PrisonerStatus status = prisonerStatus.get(id);
            if(null != status) {
                toReturn.add(status);
            }
        }
        //now sort
        Collections.sort(toReturn);
        return toReturn;
    }

    public boolean usesSalvageExchange() {
        return (getMission() instanceof Contract) && ((Contract)getMission()).isSalvageExchange();
    }

    /**
     * This object is used to track the status of a particular personnel. At the present,
     * we track the person's missing status, hits, and XP
     * @author Jay Lawson
     *
     */
    public static class PersonStatus implements Comparable<PersonStatus> {
        private String name;
        private String unitName;
        private int hits;
        private boolean missing;
        private int xp;
        private ArrayList<Kill> kills;
        private boolean remove;
        private boolean pickedUp;
        private UUID personId;
        private boolean deployed;
        private boolean dead;

        public PersonStatus(String n, String u, int h, UUID id) {
            name = n;
            unitName = u;
            hits = h;
            missing = false;
            xp = 0;
            kills = new ArrayList<>();
            remove = false;
            pickedUp = false;
            personId = id;
            deployed = true;
            dead = false;
        }

        public UUID getId() {
            return personId;
        }

        public void setRemove(UUID set) {
            personId = set;
        }

        public boolean toRemove() {
            return remove;
        }

        public void setRemove(boolean set) {
            remove = set;
        }

        public String getName() {
            return name;
        }

        public String getUnitName() {
            return unitName;
        }

        public int getHits() {
            return hits;
        }

        public void setHits(int h) {
            hits = h;
            if (hits >= 6) {
                setDead(true);
            } else {
                setDead(false);
            }
        }

        public boolean isDead() {
            return dead || (hits >= 6);
        }

        public void setDead(boolean dead) {
            this.dead = dead;
        }

        public boolean isMissing() {
            return missing && !isDead();
        }

        public void setMissing(boolean b) {
            missing = b;
        }

        public boolean wasPickedUp() {
            return pickedUp;
        }

        public void setPickedUp(boolean set) {
            pickedUp = set;
        }

        public int getXP() {
            if(isDead()) {
                return 0;
            }
            return xp;
        }

        public void setXP(int x) {
            xp = x;
        }

        public void addKill(Kill k) {
            kills.add(k);
        }

        public ArrayList<Kill> getKills() {
            return kills;
        }

        public void setDeployed(boolean b) {
            deployed = b;
        }

        public boolean wasDeployed() {
            return deployed;
        }

        @Override
        public String toString() {
            return unitName;
        }

        @Override
        public int compareTo(PersonStatus ostatus) {
            return unitName.compareTo(ostatus.getUnitName());
       }

    }

    /**
     * This object is used to track the status of a prisoners. We need to actually put the whole
     * person object here because we are not already tracking it on the campaign
     * @author Jay Lawson
     *
     */
    public static class PrisonerStatus extends PersonStatus {
        //for prisoners we have to track a whole person
        Person person;
        private boolean captured;

        public PrisonerStatus(String n, String u, Person p) {
            super(n, u, 0, p.getId());
            person = p;
        }

        public Person getPerson() {
            return person;
        }

        public boolean isCaptured() {
            return captured;
        }

        public void setCaptured(boolean set) {
            captured = set;
        }

    }

    /**
     * This object is used to track the status of a particular unit.
     * @author Jay Lawson
     *
     */
    public static class UnitStatus {

        private String name;
        private String chassis;
        private String model;
        private boolean totalLoss;
        private Entity entity;
        private Entity baseEntity;
        Unit unit;

        public UnitStatus(Unit unit) {
            this.unit = unit;
            this.name = unit.getName();
            chassis = unit.getEntity().getChassis();
            model = unit.getEntity().getModel();
            //assume its a total loss until we find something that says otherwise
            totalLoss = true;
            //create a brand new entity until we find one
            MechSummary summary = MechSummaryCache.getInstance().getMech(getLookupName());
            if (null != summary) {
                try {
                    entity = unit.getEntity() == null
                            ? new MechFileParser(summary.getSourceFile(), summary.getEntryName()).getEntity()
                            : unit.getEntity();
                    baseEntity = new MechFileParser(summary.getSourceFile(), summary.getEntryName()).getEntity();
                } catch (EntityLoadingException e) {
                    MekHQ.getLogger().error(getClass(), "Unit Status", e);
                }
            }
        }

        public String toString() {
            return "Unit status for: " + getName() + ", loss: " + isTotalLoss();
        }

        public String getName() {
            return name;
        }

        public String getLookupName() {
            String s = chassis + " " + model;
            s = s.trim();
            return s;
        }

        public Entity getEntity() {
            return entity;
        }

        public void assignFoundEntity(Entity e, boolean loss) {
            totalLoss = loss;
            entity = e;
        }

        public Entity getBaseEntity() {
            return baseEntity;
        }

        public void setBaseEntity(Entity baseEntity) {
            this.baseEntity = baseEntity;
        }

        public boolean isTotalLoss() {
            return totalLoss;
        }

        public void setTotalLoss(boolean b) {
            totalLoss = b;
        }

        public String getDesc() {
            return getDesc(false);
        }

        public String getDesc(boolean printSellValue) {
            if (null == entity) {
                return "Whoops, No Entity";
            }
            String color = "black";
            String status = Unit.getDamageStateName(entity.getDamageLevel(false));
            if (!Unit.isRepairable(entity)) {
                color = "rgb(190, 150, 55)";
                status = "Salvage";
            } else if (!Unit.isFunctional(entity)) {
                color = "rgb(205, 92, 92)";
                status = "Inoperable";
            } else {
                switch(entity.getDamageLevel(false)) {
                    case Entity.DMG_LIGHT:
                        color = "green";
                        break;
                    case Entity.DMG_MODERATE:
                        color = "yellow";
                        break;
                    case Entity.DMG_HEAVY:
                        color = "orange";
                        break;
                    case Entity.DMG_CRIPPLED:
                        color = "red";
                        break;
                }
            }

            if (printSellValue) {
                return "<html><b>" + getName() + "</b><br> (" + unit.getSellValue().toAmountAndSymbolString() + ") <font color='" + color + "'>"+ status + "</font></html>";
            } else {
                return "<html><b>" + getName() + "</b><br><font color='" + color + "'>"+ status + "</font></html>";
            }
        }

        public boolean isLikelyCaptured() {
            if (null == entity) {
                return false;
            }
            return Utilities.isLikelyCapture(entity);
        }
    }

    public void setEvent(GameVictoryEvent gve) {
        victoryEvent = gve;
    }

    public boolean playerHasBattlefieldControl() {
        return control;
    }
}
