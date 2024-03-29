[![CI](https://github.com/WindhoverLabs/yamcs-cfs/actions/workflows/ci.yml/badge.svg?branch=update-yamcs-tools)](https://github.com/WindhoverLabs/yamcs-cfs/actions/workflows/ci.yml)
[![Coverage Status](https://coveralls.io/repos/github/WindhoverLabs/yamcs-cfs/badge.svg?branch=rfc_1055)](https://coveralls.io/github/WindhoverLabs/yamcs-cfs?branch=rfc_1055)
# yamcs-cfs
A YAMCS plugin for [ailriner](https://github.com/WindhoverLabs/airliner).

# Table of Contents
1. [Dependencies](#dependencies)
2. [To Build](#to_build)  
3. [To Run](#to_run)
4. [Add It to your YAMCS Install](#add_it_to_yamcs)   
5. [XTCE Patterns and Conventions](#XTCE-Patterns-and-Conventions)
5. [Build Documentation](#build_documentation)


### Dependencies <a name="dependencies"></a>
- `Java 11`
- `Maven`
- `YAMCS>=5.4.3`
- `Ubuntu 16/18/20`

### To Build <a name="to_build"></a>
```
mvn install -DskipTests
```

To package it:
```
mvn package -DskipTests
mvn dependency:copy-dependencies
```

The `package` command will output a jar file at `yamcs-cfs/target`.
Note the `dependency:copy-dependencies` command; this will copy all of the jars to the `yamcs-cfs/target/dependency` directory. Very useful for integrating third-party dependencies.

### To Run <a name="to_run"></a>
For now this yamcs setup is pre-configured for airliner's `tutorial/cfs`, however do note that this can be re-configured 
by replacing the `src/main/yamcs/mdb/cfs.xml` xtce file with another one that is specific to the build of the flight
software which can be something else other than airliner. For more details on generating xtce files the easy way
look at [auto-yamcs](https://github.com/WindhoverLabs/auto-yamcs.git).

To run yamcs with the current configuration:
```
mvn yamcs:run
```
If all went well, the output of the terminal should be very similar to the following:

![yamcs](images/yamcs_output.png "yamcs output")

The yamcs web interface should be accessible at the address at the bottom of the output.

**NOTE:** The `mvn` command is invoking [maven](https://packages.ubuntu.com/xenial/maven)


### Add it to YAMCS <a name="add_it_to_yamcs"></a>
Assuming YAMCS is installed at `/opt/yamcs`:
```
wget https://github.com/WindhoverLabs/yamcs-cfs/releases/download/1.5.0/yamcs-cfs-1.5.0.jar
cp yamcs-cfs-1.5.0.jar /opt/yamcs/lib/ext
```




# XTCE Patterns and Conventions <a name="XTCE-Patterns-and-Conventions"></a>
While we strive for 100% XTCE compliance, we lean towards YAMCS's version of XTCE, which is *almost* fully XTCE compliant. Below are the specifications of the patterns, naming conventions and overall structure we follow  to generate our XTCE files .


## Namespaces
As the XTCE standard mandates, our namespaces are enclosed with the `<xtce:SpaceSystem>` tag. More specfically, we have a `BaseType` namespace. This namespace has *all* of the necessary base types that a SpaceSystem may use. Please note that we use the terms `Namespace` and `SpaceSystem` interchangeably. A base type is something that is not *usually* user-defined, but is rather machine-defined. Below is a table with examples of base types that may be part of our `BaseType` namespace.

|Name | Description  | Size in bits   | Encoding   | Endianness   |
|---|---|---|---|---|
| uint1_LE | An unsigned integer. | 1   | unsigned  | Little Endian  |
|uint16_BE| An unsigned integer.  |  16 | unsigned  | Big endian   |
|char160_LE| A string with 160 bits, or 20 characters. | 160  | UTF-8  | Little Endian  |

Our naming conventions for base types will *always* be [type][number_of_bits][under_score][Endianness] just lke you see in the table above.

Please note that these base types are nearly identical to what you'd find in a programming language like C. That is because that is what they are meant to represent. Our goal is to auto-generate our XTCE files by extracting data(structs, variables, etc) from binary and executable files. Our reasons for this approach are simple; writing XTCE files by hand is error-prone and time consuming. At the moment the tool chain that will allow us to do this is *almost* done. One of those tools is [juicer](https://github.com/WindhoverLabs/juicer/tree/develop), which extracts DWARF data from ELF files, such as executable binary files. 

### ParameterTypes
Our paramter types are *always* defined under `<TelemetryMetaData>`. The naming of our parameter types follows the convention shown in the table above.

### ArgumentTypes
Our argument types, as per XTCE design and standard, are *always* defined under `<CommandMetaData>`. The naming of our arguments types follows the convention shown in the table above.


## User-defined Types
`EnumeratedParameterType`, `AggregateParamterType`, `AggregateArgumentType` and `EnumeratedArgumentType` types are what we consider user-defined types. This means that these types are *not* part of the `BaseType` namespace.  `EnumeratedParameterType` and `AggregateParamterType` types are *always* defined on `<TelemetryMetaData>`. `AggregateArgumentType` and  `EnumeratedArgumentType` are *always* defined on `<CommandMetaData>`. 


## Arrays

## Standalone arrays
We define Standalone arrays as arrays whose size can be known at compile-time. An example of standalone arrays is `int points[128];`. Dynamic arrays(such as a runtime-allocated array by a malloc/new call) are not meant to be extracted by tools like `Juicer`. Therefore, do not expect our auto-generated XTCE files to have anything useful on dynamic arrays.
Statically allocated standalone arrays can take the form of a `ArrayParamterType`, which as stated before will *always* appear as part of `<TelemetryMetaData>`. They can also be of the form `ArrayArgumentType`, which will *always* appear as part of `<CommandMetaData>`.  Our naming conventions for user-defined types wil *always* be [name][under_score]["t"]. The token "t" is an invariant; ALL user-defined types will have the character "t" appended at the end.

### Arrays Inside Structures(*AggregateType)

As of the YAMCS version we use at the moment(5.1.3), arrays inside `AggregateParamterType` or `AggregateArgumentType` are *not* supported. Because of this, we have to come up with our own paradigm of defining arrays inside a structure. Here is an example of how we define an array inside a structure:
```
	<AggregateParameterType name="Payload_t">
				<MemberList>
					<Member name="CommandCounter" typeRef="cfs-ccsds/BaseType/uint8_LE"></Member>
					<Member typeRef="CFE_EVS_AppTlmData_t" name="AppData[0]"></Member>
					<Member typeRef="CFE_EVS_AppTlmData_t" name="AppData[1]"></Member>
					<Member typeRef="CFE_EVS_AppTlmData_t" name="AppData[2]"></Member>
					<Member typeRef="CFE_EVS_AppTlmData_t" name="AppData[3]"></Member>
					<Member typeRef="CFE_EVS_AppTlmData_t" name="AppData[4]"></Member>
					<Member typeRef="CFE_EVS_AppTlmData_t" name="AppData[5]"></Member>
				</MemberList>
			</AggregateParameterType>
```
Here we have a structure called `Payload_t`(notice the naming convention explained above). It has a field called `CommandCounter` and an array that has 6 elements of type `CFE_EVS_AppTlmData_t`. We tried our best to make the naming convention for arrays intuitive. As you can see it looks like a regular array access in code!

## Base types(Intrinsic types)
The types shown above such as uint16_LE are what is known as a base type. These are the types that represent things like int, float, char, etc in code. As mentioned above these *will* always be part of the `BaseType` namespace. Below is a table with all of our base types. Please note that this table is subject as we have not fully standarized our xtce format yet.
The endianness cells marked with a "*" at the end are _exclusively_ describing _just_ bit ordering and NOT byte-ordering.
As you see on the table this just applies to the types that are 8 bits or less as byte-ordering is irrelevant for these.
We consider _bit_ ordering for all of our types for the rare case where there is an architecture that takes into count bit ordering.


|Name | Description  | Size in bits   | Encoding   | Endianness   |
|---|---|---|---|---|
| uint1_BE | An unsigned integer. | 1   | unsigned  | Big Endian*  |
| uint1_LE | An unsigned integer. | 1   | unsigned  | Big Endian*  |
| uint2_BE | An unsigned integer. | 2   | unsigned  | Big Endian*  |
| uint2_LE | An unsigned integer. | 2   | unsigned  | Little Endian*  |
| int2_BE | A signed integer. | 2   | signed  | Big Endian*  |
| int2_LE | A signed integer. | 2   | signed  | Little Endian*  |
| uint3_BE | An unsigned integer. | 3   | unsigned  | Big Endian*  |
| uint3_LE | An unsigned integer. | 3   | unsigned  | Little Endian*  |
| int3_BE | A signed integer. | 3   | signed  | Big Endian*  |
| int3_LE | A signed integer. | 3   | signed  | Little Endian*  |
| uint4_BE | An unsigned integer. | 4   | unsigned  | Big Endian*  |
| uint4_LE | An unsigned integer. | 4   | unsigned  | Little Endian*  |
| int4_BE | A signed integer. | 4   | signed  | Big Endian*  |
| int4_LE | A signed integer. | 4   | signed  | Little Endian*  |
| uint5_BE | An unsigned integer. | 5   | unsigned  | Big Endian*  |
| uint5_LE | An unsigned integer. | 5   | unsigned  | Little Endian*  |
| int5_BE | A signed integer. | 5  | signed  | Big Endian*  |
| int5_LE | A signed integer. | 5   | signed  | Little Endian*  |
| uint6_BE | An unsigned integer. | 6   | unsigned  | Big Endian*  |
| uint6_LE | An unsigned integer. | 6   | unsigned  | Little Endian*  |
| int6_BE | A signed integer. | 6   | signed  | Big Endian*  |
| int6_LE | A signed integer. | 6   | signed  | Little Endian*  |
| uint7_BE | An unsigned integer. | 7   | unsigned  | Big Endian*  |
| uint7_LE | An unsigned integer. | 7   | unsigned  | Little Endian*  |
| int7_BE | A signed integer. | 7   | signed  | Big Endian*  |
| int7_LE | A signed integer. | 7   | signed  | Little Endian*  |
| uint8_BE | An unsigned integer. | 8   | unsigned  | Big Endian*  |
| uint8_LE | An unsigned integer. | 8   | unsigned  | Little Endian*  |
| int8_BE | A signed integer. | 8   | signed  | Big Endian*  |
| int8_LE | A signed integer. | 8   | signed  | Little Endian*  |
| uint9_BE | An unsigned integer. | 9   | unsigned  | Big Endian  |
| uint9_LE | An unsigned integer. | 9   | unsigned  | Little Endian  |
| int9_BE | A signed integer. | 9   | signed  | Big Endian  |
| int9_LE | A signed integer. | 9   | signed  | Little Endian  |
| uint10_BE | An unsigned integer. | 10   | unsigned  | Big Endian  |
| uint10_LE | An unsigned integer. | 10   | unsigned  | Little Endian  |
| int10_BE | A signed integer. | 10   | signed  | Big Endian  |
| int10_LE | A signed integer. | 10   | signed  | Little Endian  |
| uint11_BE | An unsigned integer. | 11   | unsigned  | Big Endian  |
| uint11_LE | An unsigned integer. | 11   | unsigned  | Little Endian  |
| int11_BE | A signed integer. | 11   | signed  | Big Endian  |
| int11_LE | A signed integer. | 11   | signed  | Little Endian  |
| uint12_BE | An unsigned integer. | 12   | unsigned  | Big Endian  |
| uint12_LE | An unsigned integer. | 12   | unsigned  | Little Endian  |
| int12_BE | A signed integer. | 12   | signed  | Big Endian  |
| int12_LE | A signed integer. | 12   | signed  | Little Endian  |
| uin13_BE | An unsigned integer. | 13   | unsigned  | Big Endian  |
| uint13_LE | An unsigned integer. | 13   | unsigned  | Little Endian  |
| int13_BE | A signed integer. | 13   | signed  | Big Endian  |
| int13_LE | A signed integer. | 13   | signed  | Little Endian  |
| uint14_BE | An unsigned integer. | 14   | unsigned  | Big Endian  |
| uint14_LE | An unsigned integer. | 14   | unsigned  | Little Endian  |
| int14_BE | A signed integer. | 14   | signed  | Big Endian  |
| int14_LE | A signed integer. | 14   | signed  | Little Endian  |
| uint15_BE | An unsigned integer. | 15   | unsigned  | Big Endian  |
| uint15_LE | An unsigned integer. | 15   | unsigned  | Little Endian  |
| int15_BE | A signed integer. | 15   | signed  | Big Endian  |
| int15_LE | A signed integer. | 15   | signed  | Little Endian  |
| uint16_BE | An unsigned integer. | 16   | unsigned  | Big Endian  |
| uint16_LE | An unsigned integer. | 16   | unsigned  | Little Endian  |
| int16_BE | A signed integer. | 16   | signed  | Big Endian  |
| int16_LE | A signed integer. | 16   | signed  | Little Endian  |
| uint17_BE | An unsigned integer. | 17   | unsigned  | Big Endian  |
| uint17_LE | An unsigned integer. | 17   | unsigned  | Little Endian  |
| int17_BE | A signed integer. | 17   | signed  | Big Endian  |
| int17_LE | A signed integer. | 17   | signed  | Little Endian  |
| uint18_BE | An unsigned integer. | 18   | unsigned  | Big Endian  |
| uint18_LE | An unsigned integer. | 18   | unsigned  | Little Endian  |
| int18_BE | A signed integer. | 18   | signed  | Big Endian  |
| int18_LE | A signed integer. | 18   | signed  | Little Endian  |
| uint19_BE | An unsigned integer. | 19   | unsigned  | Big Endian  |
| uint19_LE | An unsigned integer. | 19   | unsigned  | Little Endian  |
| int19_BE | A signed integer. | 19   | signed  | Big Endian  |
| int19_LE | A signed integer. | 19   | signed  | Little Endian  |
| uint20_BE | An unsigned integer. | 20   | unsigned  | Big Endian  |
| uint20_LE | An unsigned integer. | 20   | unsigned  | Little Endian  |
| int20_BE | A signed integer. | 20   | signed  | Big Endian  |
| int20_LE | A signed integer. | 20   | signed  | Little Endian  |
| uint21_BE | An unsigned integer. | 21   | unsigned  | Big Endian  |
| uint21_LE | An unsigned integer. | 21   | unsigned  | Little Endian  |
| int21_BE | A signed integer. | 21   | signed  | Big Endian  |
| int21_LE | A signed integer. | 21   | signed  | Little Endian  |
| uint22_BE | An unsigned integer. | 22   | unsigned  | Big Endian  |
| uint22_LE | An unsigned integer. | 22   | unsigned  | Little Endian  |
| int22_BE | A signed integer. | 22   | signed  | Big Endian  |
| int22_LE | A signed integer. | 22   | signed  | Little Endian  |
| uint23_BE | An unsigned integer. |   23 | unsigned  | Big Endian  |
| uint23_LE | An unsigned integer. |  23  | unsigned  | Little Endian  |
| int23_BE | A signed integer. |   23 | signed  | Big Endian  |
| int23_LE | A signed integer. |  23  | signed  | Little Endian  |
| uint24_BE | An unsigned integer. |  24  | unsigned  | Big Endian  |
| uint24_LE | An unsigned integer. |  24  | unsigned  | Little Endian  |
| int24_BE | A signed integer. |  24  | signed  | Big Endian  |
| int24_LE | A signed integer. |  24  | signed  | Little Endian  |
| uint25_BE | An unsigned integer. |  25  | unsigned  | Big Endian  |
| uint25_LE | An unsigned integer. |  25  | unsigned  | Little Endian  |
| int25_BE | A signed integer. |  25  | signed  | Big Endian  |
| int25_LE | A signed integer. |  25  | signed  | Little Endian  |
| uint26_BE | An unsigned integer. |  26  | unsigned  | Big Endian  |
| uint26_LE | An unsigned integer. |  26  | unsigned  | Little Endian  |
| int26_BE | A signed integer. |  26  | signed  | Big Endian  |
| int26_LE | A signed integer. |  26  | signed  | Little Endian  |
| uint27_BE | An unsigned integer. |   27 | unsigned  | Big Endian  |
| uint27_LE | An unsigned integer. | 27   | unsigned  | Little Endian  |
| int27_BE | A signed integer. |  27  | signed  | Big Endian  |
| int27_LE | A signed integer. |   27 | signed  | Little Endian  |
| uint28_BE | An unsigned integer. |  28  | unsigned  | Big Endian  |
| uint28_LE | An unsigned integer. | 28   | unsigned  | Little Endian  |
| int28_BE | A signed integer. |  28  | signed  | Big Endian  |
| int28_LE | A signed integer. |   28 | signed  | Little Endian  |
| uint29_BE | An unsigned integer. |  29  | unsigned  | Big Endian  |
| uint29_LE | An unsigned integer. |  29  | unsigned  | Little Endian  |
| int29_BE | A signed integer. |  29  | signed  | Big Endian  |
| int29_LE | A signed integer. |  29  | signed  | Little Endian  |
| uint30_BE | An unsigned integer. |  30  | unsigned  | Big Endian  |
| uint30_LE | An unsigned integer. | 30   | unsigned  | Little Endian  |
| int30_BE | A signed integer. |  30  | signed  | Big Endian  |
| int30_LE | A signed integer. | 30   | signed  | Little Endian  |
| uint31_BE | An unsigned integer. |   31 | unsigned  | Big Endian  |
| uint31_LE | An unsigned integer. |  31  | unsigned  | Little Endian  |
| int31_BE | A signed integer. |  31  | signed  | Big Endian  |
| int31_LE | A signed integer. | 31   | signed  | Little Endian  |
| uint32_BE | An unsigned integer. |   32 | unsigned  | Big Endian  |
| uint32_LE | An unsigned integer. |  32  | unsigned  | Little Endian  |
| int32_BE | A signed integer. |  32  | signed  | Big Endian  |
| int32_LE | A signed integer. |  32  | signed  | Little Endian  |
| uint33_BE | An unsigned integer. |  33  | unsigned  | Big Endian  |
| uint33_LE | An unsigned integer. |   33 | unsigned  | Little Endian  |
| int33_BE | A signed integer. |  33  | signed  | Big Endian  |
| int33_LE | A signed integer. | 33   | signed  | Little Endian  |
| uint34_BE | An unsigned integer. |   34 | unsigned  | Big Endian  |
| uint34_LE | An unsigned integer. |  34  | unsigned  | Little Endian  |
| int34_BE | A signed integer. |  34  | signed  | Big Endian  |
| int34_LE | A signed integer. |   34 | signed  | Little Endian  |
| uint34_BE | An unsigned integer. |  34  | unsigned  | Big Endian  |
| uint34_LE | An unsigned integer. |  34  | unsigned  | Little Endian  |
| int35_BE | A signed integer. |  35  | signed  | Big Endian  |
| int35_LE | A signed integer. | 35 | uint_BE | An unsigned integer. |    | unsigned  | Big Endian  |
| uint35_LE | An unsigned integer. |  35  | unsigned  | Little Endian  |
| int35_BE | A signed integer. |  35  | signed  | Big Endian  |
| uint36_BE | An unsigned integer. |  36  | unsigned  | Big Endian  |
| uint36_LE | An unsigned integer. |   36 | unsigned  | Little Endian  |
| int36_BE | A signed integer. |  36  | signed  | Big Endian  |
| int36_LE | A signed integer. | 36   | signed  | Little Endian  
| uint37_BE | An unsigned integer. |   37 | unsigned  | Big Endian  |
| uint37_LE | An unsigned integer. |  37  | unsigned  | Little Endian  |
| int37_BE | A signed integer. |  37  | signed  | Big Endian  |
| int37_LE | A signed integer. |  37  | signed  | Little Endian  |
| uint38_BE | An unsigned integer. |  38  | unsigned  | Big Endian  |
| uint38_LE | An unsigned integer. | 38   | unsigned  | Little Endian  |
| int38_BE | A signed integer. |  38  | signed  | Big Endian  |
| int38_LE | A signed integer. |  38  | signed  | Little Endian  |
| uint39_BE | An unsigned integer. |   39 | unsigned  | Big Endian  |
| uint39_LE | An unsigned integer. |  39  | unsigned  | Little Endian  |
| int39_BE | A signed integer. |  39  | signed  | Big Endian  |
| int39_LE | A signed integer. |  39  | signed  | Little Endian  |
| uint40_BE | An unsigned integer. |   40 | unsigned  | Big Endian  |
| uint40_LE | An unsigned integer. |  40  | unsigned  | Little Endian  |
| int40_BE | A signed integer. |  40  | signed  | Big Endian  |
| int40_LE | A signed integer. |  40  | signed  | Little Endian  |
| uint41_BE | An unsigned integer. |   41 | unsigned  | Big Endian  |
| uint41_LE | An unsigned integer. |  41  | unsigned  | Little Endian  |
| int41_BE | A signed integer. |  41  | signed  | Big Endian  |
| int41_LE | A signed integer. |  41  | signed  | Little Endian  |
| uint42_BE | An unsigned integer. |   42 | unsigned  | Big Endian  |
| uint42_LE | An unsigned integer. |  42  | unsigned  | Little Endian  |
| int42_BE | A signed integer. |  42  | signed  | Big Endian  |
| int42_LE | A signed integer. | 42   | signed  | Little Endian 
| uint43_BE | An unsigned integer. |  43  | unsigned  | Big Endian  |
| uint43_LE | An unsigned integer. | 43   | unsigned  | Little Endian  |
| int43_BE | A signed integer. |   43 | signed  | Big Endian  |
| int43_LE | A signed integer. |  43  | signed  | Little Endian  |
| uint44_BE | An unsigned integer. |  44  | unsigned  | Big Endian  |
| uint44_LE | An unsigned integer. | 44   | unsigned  | Little Endian  |
| int44_BE | A signed integer. |  44  | signed  | Big Endian  |
| int44_LE | A signed integer. |  44  | signed  | Little Endian  |
| uint45_BE | An unsigned integer. |  45  | unsigned  | Big Endian  |
| uint45_LE | An unsigned integer. | 45   | unsigned  | Little Endian  |
| int45_BE | A signed integer. |   45 | signed  | Big Endian  |
| int45_LE | A signed integer. |  45  | signed  | Little Endian  |
| uint46_BE | An unsigned integer. |  46  | unsigned  | Big Endian  |
| uint46_LE | An unsigned integer. |  46  | unsigned  | Little Endian  |
| int46_BE | A signed integer. |   46 | signed  | Big Endian  |
| int46_LE | A signed integer. | 46   | signed  | Little Endian  |
| uint47_BE | An unsigned integer. |  47  | unsigned  | Big Endian  |
| uint47_LE | An unsigned integer. |  47  | unsigned  | Little Endian  |
| int47_BE | A signed integer. |   47 | signed  | Big Endian  |
| int47_LE | A signed integer. |  47  | signed  | Little Endian  |
| uint48_BE | An unsigned integer. |  48  | unsigned  | Big Endian  |
| uint48_LE | An unsigned integer. | 48   | unsigned  | Little Endian  |
| int48_BE | A signed integer. |  48  | signed  | Big Endian  |
| int48_LE | A signed integer. |  48  | signed  | Little Endian  
| uint49_BE | An unsigned integer. |  49  | unsigned  | Big Endian  |
| uint49_LE | An unsigned integer. |  49  | unsigned  | Little Endian  |
| int49_BE | A signed integer. |  49  | signed  | Big Endian  |
| int49_LE | A signed integer. |49    | signed  | Little Endian 
| uint50_BE | An unsigned integer. |  50  | unsigned  | Big Endian  |
| uint50_LE | An unsigned integer. | 50   | unsigned  | Little Endian  |
| int50_BE | A signed integer. |  50  | signed  | Big Endian  |
| int50_LE | A signed integer. |  50  | signed  | Little Endian  |
| uint51_BE | An unsigned integer. |   51 | unsigned  | Big Endian  |
| uint51_LE | An unsigned integer. |  51  | unsigned  | Little Endian  |
| int51_BE | A signed integer. |  51  | signed  | Big Endian  |
| int51_LE | A signed integer. |  51  | signed  | Little Endian  |
| uint52_BE | An unsigned integer. |   52 | unsigned  | Big Endian  |
| uint52_LE | An unsigned integer. | 52   | unsigned  | Little Endian  |
| int52_BE | A signed integer. |  52  | signed  | Big Endian  |
| int52_LE | A signed integer. |  52  | signed  | Little Endian  |
| uint53_BE | An unsigned integer. |  53  | unsigned  | Big Endian  |
| uint53_LE | An unsigned integer. |  53  | unsigned  | Little Endian  |
| int53_BE | A signed integer. |   53 | signed  | Big Endian  |
| int53_LE | A signed integer. |  53  | signed  | Little Endian  |
| uint54_BE | An unsigned integer. |   54 | unsigned  | Big Endian  |
| uint54_LE | An unsigned integer. |  54  | unsigned  | Little Endian  |
| int54_BE | A signed integer. |  54  | signed  | Big Endian  |
| int54_LE | A signed integer. |  54  | signed  | Little Endian  |
| uint55_BE | An unsigned integer. |   55 | unsigned  | Big Endian  |
| uint55_LE | An unsigned integer. |  55  | unsigned  | Little Endian  |
| int55_BE | A signed integer. |  55  | signed  | Big Endian  |
| int55_LE | A signed integer. |  55  | signed  | Little Endian  |
| uint56_BE | An unsigned integer. |  56  | unsigned  | Big Endian  |
| uint56_LE | An unsigned integer. | 56   | unsigned  | Little Endian  |
| int56_BE | A signed integer. |  56  | signed  | Big Endian  |
| int56_LE | A signed integer. |  56  | signed  | Little Endian  |
| uint57_BE | An unsigned integer. |  57  | unsigned  | Big Endian  |
| uint57_LE | An unsigned integer. |  57  | unsigned  | Little Endian  |
| int57_BE | A signed integer. |  57  | signed  | Big Endian  |
| int57_LE | A signed integer. |  57  | signed  | Little Endian  |
| uint58_BE | An unsigned integer. |  58  | unsigned  | Big Endian  |
| uint58_LE | An unsigned integer. |  58  | unsigned  | Little Endian  |
| int58_BE | A signed integer. |  58  | signed  | Big Endian  |
| int58_LE | A signed integer. |   58 | signed  | Little Endian  |
| uint59_BE | An unsigned integer. |  59  | unsigned  | Big Endian  |
| uint59_LE | An unsigned integer. | 59   | unsigned  | Little Endian  |
| int59_BE | A signed integer. |   59 | signed  | Big Endian  |
| int59_LE | A signed integer. |   59 | signed  | Little Endian  |
| uint60_BE | An unsigned integer. |   60 | unsigned  | Big Endian  |
| uint60_LE | An unsigned integer. | 60   | unsigned  | Little Endian  |
| int60_BE | A signed integer. |  60 | signed  | Big Endian  |
| int60_LE | A signed integer. |  60  | signed  | Little Endian  |
| uint61_BE | An unsigned integer. |  61  | unsigned  | Big Endian  |
| uint61_LE | An unsigned integer. | 61   | unsigned  | Little Endian  |
| int61_BE | A signed integer. |  61  | signed  | Big Endian  |
| int61_LE | A signed integer. |  61  | signed  | Little Endian  |
| uint62_BE | An unsigned integer. |  62  | unsigned  | Big Endian  |
| uint62_LE | An unsigned integer. |  62  | unsigned  | Little Endian  |
| int62_BE | A signed integer. |  62  | signed  | Big Endian  |
| int62_LE | A signed integer. |  62  | signed  | Little Endian  |
| uint63_BE | An unsigned integer. | 63   | unsigned  | Big Endian  |
| uint63_LE | An unsigned integer. |  63  | unsigned  | Little Endian  |
| int63_BE | A signed integer. |  63  | signed  | Big Endian  |
| int63_LE | A signed integer. |  63  | signed  | Little Endian  |
| uint64_BE | An unsigned integer. |  64  | unsigned  | Big Endian  |
| uint64_LE | An unsigned integer. |  64  | unsigned  | Little Endian  |
| int64_BE | A signed integer. | 64   | signed  | Big Endian  |
| int64_LE | A signed integer. |  64  | signed  | Little Endian  |
| float32_LE | A signed floating point number. |  32  | signed  | Little Endian  |
| float32_BE | A signed floating point number. |  32  | signed  | Big Endian  |
| boolean8_LE | A boolean. "1" means TRUE; "0" FALSE. |  8  | N/A  | Little Endian  |

## YAMCS-XTCE Quirks
Our xtce flavor adheres to yamcs. In the future, we'll try our best to design our toolchain in such a way we can
be 100% compliant with `xtce`, but at the moment we adhere to yamcs-flavored xtce _only_. As a result of this, we will 
need to deal with any quirks this yamcs-flavored xtce standard may have. Below are a list of quirks we've discovered
so far:
- It seems that yamcs has issues processing any `Paramter` entries that have a '#' in the name.

### Build Documentation <a name="build_documentation"></a>

```
cd docs/
pip3 install -r requirements.txt
make html
```

### Toggle UDP Sockets

```python
import requests
r = requests.post('http://127.0.0.1:8090/api/fsw/udp/:start',
                  json={"instance": "fsw",
                        "linkName": "tm_ground_node_udp_out"})
```

```python
import requests
r = requests.post('http://127.0.0.1:8090/api/fsw/udp/:stop',
                  json={"instance": "fsw",
                        "linkName": "tm_ground_node_udp_out"})
```


### Export CSVs

```yaml
  - class: com.windhoverlabs.yamcs.archive.CSVExporter
    name: "csv_exporter"
    args:
      bucket: "cfdpDownCH1"
      start: "2023-09-23T23:00:00.000Z"
      stop: "2023-09-24T00:10:00.000Z"
      params:
        TO_HK_TLM_MID:
          - /cfs/ppd/apps/to/TO_HK_TLM_MID.CmdCnt
          - /cfs/ppd/apps/to/TO_HK_TLM_MID.CmdErrCnt
          - /cfs/ppd/apps/to/TO_HK_TLM_MID.SentBytes
          - /cfs/ppd/apps/to/TO_HK_TLM_MID.ChannelMaxMem
          - /cfs/ppd/apps/to/TO_HK_TLM_MID.ChannelInfo[0].MemInUse
          - /cfs/ppd/apps/to/TO_HK_TLM_MID.ChannelInfo[0].PeakMemInUse
          - /cfs/ppd/apps/to/TO_HK_TLM_MID.ChannelInfo[0].TotalQueued
          - /cfs/ppd/apps/to/TO_HK_TLM_MID.ChannelInfo[0].MessagesSent
          - /cfs/ppd/apps/to/TO_HK_TLM_MID.ChannelInfo[0].SentBytes
          - /cfs/ppd/apps/to/TO_HK_TLM_MID.ChannelInfo[0].CurrentlyQueuedCnt
        PX4_VEHICLE_ATTITUDE_SETPOINT_MID:
          - /cfs/cpd/apps/px4lib/PX4_VEHICLE_ATTITUDE_SETPOINT_MID.RollBody
          - /cfs/cpd/apps/px4lib/PX4_VEHICLE_ATTITUDE_SETPOINT_MID.PitchBody
        ASPD4525_HK_TLM_MID:
          - /cfs/cpd/apps/aspd4525/ASPD4525_HK_TLM_MID.fIndicatedAirSpeed
          - /cfs/cpd/apps/aspd4525/ASPD4525_HK_TLM_MID.fTrueAirSpeed  
          - /cfs/cpd/apps/aspd4525/ASPD4525_HK_TLM_MID.fTrueAirSpeedUnfiltered
          - /cfs/cpd/apps/aspd4525/ASPD4525_HK_TLM_MID.uTemperatureCount
          - /cfs/cpd/apps/aspd4525/ASPD4525_HK_TLM_MID.uStatus
          - /cfs/cpd/apps/aspd4525/ASPD4525_HK_TLM_MID.fTemperature
          - /cfs/cpd/apps/aspd4525/ASPD4525_HK_TLM_MID.fPressureMinimum_PSI
          - /cfs/cpd/apps/aspd4525/ASPD4525_HK_TLM_MID.fPressureMaximum_PSI
          - /cfs/cpd/apps/aspd4525/ASPD4525_HK_TLM_MID.fTemperatureMinimum_Celcius
          - /cfs/cpd/apps/aspd4525/ASPD4525_HK_TLM_MID.fTemperatureMaximum_Celcius
```

**NOTE**: This documentation is subject to change as our tools evolve.  
Documented on July 5th, 2021

