/**
 * Processor for Voice Hints
 *
 * @author ab
 */
package btools.router;

import java.util.ArrayList;
import java.util.List;

public final class VoiceHintProcessor {

  double SIGNIFICANT_ANGLE = 22.5;
  double INTERNAL_CATCHING_RANGE = 2.;

  // private double catchingRange; // range to catch angles and merge turns
  private boolean explicitRoundabouts;

  public VoiceHintProcessor(double catchingRange, boolean explicitRoundabouts) {
    // this.catchingRange = catchingRange;
    this.explicitRoundabouts = explicitRoundabouts;
  }

  private float sumNonConsumedWithinCatchingRange(List<VoiceHint> inputs, int offset) {
    double distance = 0.;
    float angle = 0.f;
    while (offset >= 0 && distance < INTERNAL_CATCHING_RANGE) {
      VoiceHint input = inputs.get(offset--);
      if (input.turnAngleConsumed) {
        break;
      }
      angle += input.goodWay.turnangle;
      distance += input.goodWay.linkdist;
      input.turnAngleConsumed = true;
    }
    return angle;
  }


  /**
   * process voice hints. Uses VoiceHint objects
   * for both input and output. Input is in reverse
   * order (from target to start), but output is
   * returned in travel-direction and only for
   * those nodes that trigger a voice hint.
   * <p>
   * Input objects are expected for every segment
   * of the track, also for those without a junction
   * <p>
   * VoiceHint objects in the output list are enriched
   * by the voice-command, the total angle and the distance
   * to the next hint
   *
   * @param inputs tracknodes, un reverse order
   * @return voice hints, in forward order
   */
  public List<VoiceHint> process(List<VoiceHint> inputs) {
    List<VoiceHint> results = new ArrayList<>();
    double distance = 0.;
    float roundAboutTurnAngle = 0.f; // sums up angles in roundabout

    int roundaboutExit = 0;
    int roundaboudStartIdx = -1;

    for (int hintIdx = 0; hintIdx < inputs.size(); hintIdx++) {
      VoiceHint input = inputs.get(hintIdx);

      if (input.cmd == VoiceHint.BL) {
        results.add(input);
        continue;
      }
      float turnAngle = input.goodWay.turnangle;
      distance += input.goodWay.linkdist;
      int currentPrio = input.goodWay.getPrio();
      int oldPrio = input.oldWay.getPrio();
      int minPrio = Math.min(oldPrio, currentPrio);

      boolean isLink2Highway = input.oldWay.isLinktType() && !input.goodWay.isLinktType();
      boolean isHighway2Link = !input.oldWay.isLinktType() && input.goodWay.isLinktType();

      if (explicitRoundabouts && input.oldWay.isRoundabout()) {
        if (roundaboudStartIdx == -1) roundaboudStartIdx = hintIdx;
        roundAboutTurnAngle += sumNonConsumedWithinCatchingRange(inputs, hintIdx);
        boolean isExit = roundaboutExit == 0; // exit point is always exit
        if (input.badWays != null) {
          for (MessageData badWay : input.badWays) {
            if (!badWay.isBadOneway() && badWay.isGoodForCars() && Math.abs(badWay.turnangle) < 120.) {
              isExit = true;
            }
          }
        }
        if (isExit) {
          roundaboutExit++;
        }
        continue;
      }
      if (roundaboutExit > 0) {
        roundAboutTurnAngle += sumNonConsumedWithinCatchingRange(inputs, hintIdx);
        double startTurn = (roundaboudStartIdx != -1 ? inputs.get(roundaboudStartIdx).goodWay.turnangle : turnAngle);
        input.angle = roundAboutTurnAngle;
        input.distanceToNext = distance;
        input.roundaboutExit = startTurn < 0 ? -roundaboutExit : roundaboutExit;
        distance = 0.;
        results.add(input);
        roundAboutTurnAngle = 0.f;
        roundaboutExit = 0;
        roundaboudStartIdx = -1;
        continue;
      }
      int maxPrioAll = -1; // max prio of all detours
      int maxPrioCandidates = -1; // max prio of real candidates

      float maxAngle = -180.f;
      float minAngle = 180.f;
      float minAbsAngeRaw = 180.f;

      boolean isBadwayLink = false;

      if (input.badWays != null) {
        for (MessageData badWay : input.badWays) {
          int badPrio = badWay.getPrio();
          float badTurn = badWay.turnangle;
          if (badWay.isLinktType()) {
            isBadwayLink = true;
          }
          boolean isBadHighway2Link = !input.oldWay.isLinktType() && badWay.isLinktType();

          if (badPrio > maxPrioAll && !isBadHighway2Link) {
            maxPrioAll = badPrio;
          }

          if (badWay.costfactor < 20.f && Math.abs(badTurn) < minAbsAngeRaw) {
            minAbsAngeRaw = Math.abs(badTurn);
          }

          if (badPrio < minPrio) {
            continue; // ignore low prio ways
          }

          if (badWay.isBadOneway()) {
            continue; // ignore wrong oneways
          }

          if (Math.abs(badTurn) - Math.abs(turnAngle) > 80.f) {
            continue; // ways from the back should not trigger a slight turn
          }

          if (badPrio > maxPrioCandidates) {
            maxPrioCandidates = badPrio;
            input.maxBadPrio = Math.max(input.maxBadPrio, badPrio);
          }
          if (badTurn > maxAngle) {
            maxAngle = badTurn;
          }
          if (badTurn < minAngle) {
            minAngle = badTurn;
          }
        }
      }

      boolean hasSomethingMoreStraight = (Math.abs(turnAngle) - minAbsAngeRaw) > 20.;
      //boolean hasSomethingMoreStraight = (Math.abs(turnAngle - minAbsAngeRaw)) > 20.;

      // unconditional triggers are all junctions with
      // - higher detour prios than the minimum route prio (except link->highway junctions)
      // - or candidate detours with higher prio then the route exit leg
      boolean unconditionalTrigger = hasSomethingMoreStraight ||
        (maxPrioAll > minPrio && !isLink2Highway) ||
        (maxPrioCandidates > currentPrio) ||
        VoiceHint.is180DegAngle(turnAngle) ||
        (!isHighway2Link && isBadwayLink && Math.abs(turnAngle) > 5.f) ||
        (isHighway2Link && !isBadwayLink && Math.abs(turnAngle) < 5.f);

      // conditional triggers (=real turning angle required) are junctions
      // with candidate detours equal in priority than the route exit leg
      boolean conditionalTrigger = maxPrioCandidates >= minPrio;

      if (unconditionalTrigger || conditionalTrigger) {
        input.angle = turnAngle;
        input.calcCommand();
        boolean isStraight = input.cmd == VoiceHint.C;
        input.needsRealTurn = (!unconditionalTrigger) && isStraight;

        // check for KR/KL
        if (Math.abs(turnAngle) > 5.) { // don't use too small angles
          if (maxAngle < turnAngle && maxAngle > turnAngle - 45.f - (Math.max(turnAngle, 0.f))) {
            input.cmd = VoiceHint.KR;
          }
          if (minAngle > turnAngle && minAngle < turnAngle + 45.f - (Math.min(turnAngle, 0.f))) {
            input.cmd = VoiceHint.KL;
          }
        }

        input.angle = sumNonConsumedWithinCatchingRange(inputs, hintIdx);
        input.distanceToNext = distance;
        distance = 0.;
        results.add(input);
      }
      if (results.size() > 0 && distance < INTERNAL_CATCHING_RANGE) { //catchingRange
        results.get(results.size() - 1).angle += sumNonConsumedWithinCatchingRange(inputs, hintIdx);
      }
    }

    // go through the hint list again in reverse order (=travel direction)
    // and filter out non-significant hints and hints too close to its predecessor

    List<VoiceHint> results2 = new ArrayList<>();
    int i = results.size();
    while (i > 0) {
      VoiceHint hint = results.get(--i);
      if (hint.cmd == 0) {
        hint.calcCommand();
      }
      if (!(hint.needsRealTurn && (hint.cmd == VoiceHint.C || hint.cmd == VoiceHint.BL))) {
        double dist = hint.distanceToNext;
        // sum up other hints within the catching range (e.g. 40m)
        while (dist < INTERNAL_CATCHING_RANGE && i > 0) {
          VoiceHint h2 = results.get(i - 1);
          dist = h2.distanceToNext;
          hint.distanceToNext += dist;
          hint.angle += h2.angle;
          i--;
          if (h2.isRoundabout()) { // if we hit a roundabout, use that as the trigger
            h2.angle = hint.angle;
            hint = h2;
            break;
          }
        }

        if (!explicitRoundabouts) {
          hint.roundaboutExit = 0; // use an angular hint instead
        }
        hint.calcCommand();
        results2.add(hint);
      } else if (hint.cmd == VoiceHint.BL) {
        results2.add(hint);
      } else {
        if (results2.size() > 0)
          results2.get(results2.size() - 1).distanceToNext += hint.distanceToNext;
      }
    }
    return results2;
  }

  public List<VoiceHint> postProcess(List<VoiceHint> inputs, double catchingRange, double minRange) {
    List<VoiceHint> results = new ArrayList<>();
    double distance = 0;
    VoiceHint inputLast = null;
    for (int hintIdx = 0; hintIdx < inputs.size(); hintIdx++) {
      VoiceHint input = inputs.get(hintIdx);
      VoiceHint nextInput = null;
      if (hintIdx + 1 < inputs.size()) {
        nextInput = inputs.get(hintIdx + 1);
      }

      if (nextInput == null) {
        if (input.cmd == VoiceHint.C && !input.goodWay.isLinktType()) {
          if (input.goodWay.getPrio() < input.maxBadPrio) {
            results.add(input);
          } else {
            if (inputLast != null) { // when drop add distance to last
              inputLast.distanceToNext += input.distanceToNext;
            }
            continue;
          }
        } else {
          results.add(input);
        }
      } else {
        if (input.cmd == VoiceHint.C && !input.goodWay.isLinktType()) {
          if (input.goodWay.getPrio() < input.maxBadPrio) {
            results.add(input);
          } else {
            if (inputLast != null) { // when drop add distance to last
              inputLast.distanceToNext += input.distanceToNext;
            }
          }
        } else if (input.distanceToNext < catchingRange) {
          int badWayPrio = 0;
          double dist = input.distanceToNext;
          float angles = input.angle;
          int i = 1;
          boolean save = false;

          dist += nextInput.distanceToNext;
          angles += nextInput.angle;

          if (input.cmd == VoiceHint.C && !input.goodWay.isLinktType()) {
            if (input.goodWay.getPrio() < input.maxBadPrio) {
              save = true;
            }
          } else if (VoiceHint.is180DegAngle(input.angle)) { //|| VoiceHint.is180DegAngle(nextInput.angle)) {  // u-turn, 180 degree
            //System.out.println("uturn < dist next!=null " + input.indexInTrack);
            save = true;
          } else if (Math.abs(angles) > 180 - SIGNIFICANT_ANGLE) { // u-turn, collects e.g. two left turns in range
            input.angle = angles;
            input.calcCommand();
            input.distanceToNext += nextInput.distanceToNext;
            save = true;
            hintIdx++;
          } else if (Math.abs(angles) < SIGNIFICANT_ANGLE && input.distanceToNext < minRange) {
            input.angle = angles;
            input.calcCommand();
            input.distanceToNext += nextInput.distanceToNext;
            save = true;
          } else if (Math.abs(input.angle) > SIGNIFICANT_ANGLE) {
            results.add(input); // add when last
            save = false;
          } else if (Math.abs(input.angle) < SIGNIFICANT_ANGLE) {
            results.add(input); // add when last
            save = false;
          } else {
            if (inputLast != null) { // when drop add distance to last
              inputLast.distanceToNext += input.distanceToNext;
            }
            save = false;
          }

          if (save) {
            results.add(input); // add when last
          }
        } else {
          results.add(input);
        }
      }
      inputLast = input;
    }

    return results;
  }


}
