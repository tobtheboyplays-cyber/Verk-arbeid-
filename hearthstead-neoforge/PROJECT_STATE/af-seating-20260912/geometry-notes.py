# Exact Another Furniture 4.0.2 collision solids for an untucked north-facing chair/table.
# Units are local block coordinates; read from ChairBlock/TableBlock static initializers.
CHAIR = [(.125,0,.125,.875,.4375,.875), (.125,.4375,.75,.875,1,.875)]
TABLE = [(0,.8125,0,1,1,1), (.875,0,0,1,.875,.125), (.875,0,.875,1,.875,1), (0,0,.875,.125,.875,1), (0,0,0,.125,.875,.125)]
# The conservative body corridor follows from the real solids and the production torso half-depth 2.5/16.
# table top ends at z=7.0; chair back begins at z=7.75.
TORSO_HALF_DEPTH=2.5/16
print('origin z safe corridor after torso is above table top:', 7+TORSO_HALF_DEPTH, '< z <', 7.75-TORSO_HALF_DEPTH)
print('settled anchor z:', 7.5 + (0.125-(5**.5)/12))