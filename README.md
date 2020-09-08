# yamcs-cfs
A YAMCS plugin for [ailriner](https://github.com/WindhoverLabs/airliner).

# Table of Contents
1. [Dependencies](#dependencies)
2. [XTCE Patterns and Conventions](#XTCE-Patterns-and-Conventions)

### Dependencies <a name="dependencies"></a>
- `Java 11`
- `Maven`
- `YAMCS 5.1.1`

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
Our paramter types are *always* defined under `<xtce:TelemetryMetaData>`. The naming of our parameter types follows the convention shown in the table above.

### ArgumentTypes
Our argument types, as per XTCE design and standard, are *always* defined under `<xtce:CommandMetaData>`. The naming of our arguments types follows the convention shown in the table above.


## User-defined Types
`EnumeratedParameterType`, `AggregateParamterType`, `AggregateArgumentType` and `EnumeratedArgumentType` types are what we consider user-defined types. This means that these types are *not* part of the `BaseType` namespace.  `EnumeratedParameterType` and `AggregateParamterType` types are *always* defined on `<xtce:TelemetryMetaData>`. `AggregateArgumentType` and  `EnumeratedArgumentType` are *always* defined on `<xtce:CommandMetaData>`. 


## Arrays

## Standalone arrays
We define Standalone arrays as arrays whose size can be known at compile-time. An example of standalone arrays is `int points[128];`. Dynamic arrays(such as a runtime-allocated array by a malloc/new call) are not meant to be extracted by tools like `Juicer`. Therefore, do not expect our auto-generated XTCE files to have anything useful on dynamic arrays.
Statically allocated standalone arrays can take the form of a `ArrayParamterType`, which as stated before will *always* appear as part of `<xtce:TelemetryMetaData>`. They can also be of the form `ArrayArgumentType`, which will *always* appear as part of `<xtce:CommandMetaData>`.  Our naming conventions for user-defined types wil *always* be [name][under_score]["t"]. The token "t" is an invariant; ALL types will have the character "t" appended at the end.

### Arrays Inside Structures(*AggregateType)

As of the YAMCS version we use at the moment(5.1.1), arrays inside `AggregateParamterType` or `AggregateArgumentType` are *not* supported. Because of this, we have to come up with our own paradigm of defining arrays inside a structure. Here is an example of how we define an array inside a structure:
```
	<xtce:AggregateParameterType name="Payload_t">
				<xtce:MemberList>
					<xtce:Member name="CommandCounter" typeRef="cfs-ccsds/BaseType/uint8_LE"></xtce:Member>
					<xtce:Member typeRef="CFE_EVS_AppTlmData_t" name="AppData[0]"></xtce:Member>
					<xtce:Member typeRef="CFE_EVS_AppTlmData_t" name="AppData[1]"></xtce:Member>
					<xtce:Member typeRef="CFE_EVS_AppTlmData_t" name="AppData[2]"></xtce:Member>
					<xtce:Member typeRef="CFE_EVS_AppTlmData_t" name="AppData[3]"></xtce:Member>
					<xtce:Member typeRef="CFE_EVS_AppTlmData_t" name="AppData[4]"></xtce:Member>
					<xtce:Member typeRef="CFE_EVS_AppTlmData_t" name="AppData[5]"></xtce:Member>
				</xtce:MemberList>
			</xtce:AggregateParameterType>
		</xt
```
Here we have a structure called `Payload_t`(notice the naming convention explained above). It has a field called `CommandCounter` and an array that has 6 elements of type `CFE_EVS_AppTlmData_t`. We tried our best to make the naming convention for arrays intuitive. As you can see it looks like a regular array access in code!

**NOTE**: This documentation is subject to change as our toolchain evolves.

Documented on September 7, 2020
