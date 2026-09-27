# Apply notes

Copy `stage-src/src/main/java/com/hearthstead/event/HearthsteadDayLength.java` and
`stage-src/src/test/java/com/hearthstead/event/HearthsteadDayLengthTest.java` to the
matching repository paths.  The event self-registers.

Activation is explicit for a server launch only:

```text
-Dhearthstead.longDays=true
```

The default is false, so test worlds and existing servers retain vanilla time unless
that exact JVM property is present.  The service changes only Overworld `dayTime`; it
never changes `gameTime`.  A 3-tick normal vanilla daylight advance becomes 2 ticks,
so one visible day lasts 1.5 times as long.  A daylight pause or a non-one-tick jump
from sleep or `/time` does not receive a correction.
