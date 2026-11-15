/* Saved model parameters; source recordings and per-event review labels remain local. */
(function(root){
'use strict';
const filter={
  "id": "badminton-reviewed-hit-filter-v1",
  "sport": "badminton",
  "name": "Badminton hit filter",
  "version": "badminton-personal-screen-v1",
  "baseAlgorithm": "badminton-wrist-heuristic-v1",
  "recordingName": "badminton-fullrate-2026-11-15T07-42-29.347660Z.csv",
  "features": [
    "log wrist gyro RMS",
    "log acceleration-change / accel RMS"
  ],
  "tree": {
    "hits": 50,
    "nonHits": 18,
    "n": 68,
    "feature": 0,
    "threshold": 6.039941930735003,
    "left": {
      "hits": 5,
      "nonHits": 15,
      "n": 20,
      "feature": 1,
      "threshold": 3.961591315713682,
      "left": {
        "hits": 0,
        "nonHits": 5,
        "n": 5
      },
      "right": {
        "hits": 5,
        "nonHits": 10,
        "n": 15
      }
    },
    "right": {
      "hits": 45,
      "nonHits": 3,
      "n": 48,
      "feature": 1,
      "threshold": 3.1499546188992142,
      "left": {
        "hits": 0,
        "nonHits": 3,
        "n": 3
      },
      "right": {
        "hits": 45,
        "nonHits": 0,
        "n": 45
      }
    }
  },
  "reviewed": 68,
  "labels": {
    "hits": 50,
    "nonHits": 18
  },
  "diagnostic": {
    "available": true,
    "trainingReviewed": 45,
    "reviewed": 23,
    "keptHits": 15,
    "flaggedHits": 1,
    "keptNonHits": 2,
    "flaggedNonHits": 5,
    "meaning": "Chronological within-session diagnostic only; detector design used this session. Not independent validation or session hit recall."
  },
  "meaning": "Personal motion-screen flags from reviewed candidates. Human labels override flags; new weak hits may be flagged. No contact probability or automatic bend-down classification.",
  "fittingAudit": {
    "reviewed": 68,
    "keptHits": 45,
    "flaggedHits": 5,
    "keptNonHits": 0,
    "flaggedNonHits": 18
  }
};
if(typeof module!=='undefined')module.exports=filter;else root.BadmintonHitFilter=filter;
})(globalThis);
