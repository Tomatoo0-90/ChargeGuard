# Hilt, Room, DataStore, Compose and Navigation ship their own consumer rules.
# Keep enum names used for persistence (stored by name in DataStore/Room).
-keepclassmembers enum com.chargeguard.domain.model.** { *; }
