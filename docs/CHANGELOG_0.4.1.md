# 0.4.1

- Fixed round-trip SMS parsing when the outbound leg has no carriage/seat assignment.
- Prevented reservation data from leaking between `Ida` and `Volta` legs.
- Fixed assignment parsing when a direction block contains a service date before the train number.
- Replaced the deprecated untyped `Bundle.getParcelableArray()` call on Android 13+.
