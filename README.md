# Volcanic Mason
An addon for Minecolonies. The Volcanic Mason is a worker that mines cobblestone, stone, basalt and obsidian.  
It can generate these blocks using water/lava block generators to infinity supply your colony!  

## Getting started
To get started, build a library and research the Volcanic Mason. The Library will tell you what is needed to unlock it.  
To build the Volcanic Mason's Hut, open the build tool and select the Volcanic Mason style pack.

## Crushing basalt
Besides the Volcanic Mason worker, this addon adds one more recipe for the Crusher. It allows you to break down basalt 
into soul sand.

## Styles
At the current time, only 2 styles (Minecolonies and Fortress) support the Volcanic Mason, but no fear, you can combine styles with no problems! 
This is because I do not have the time (or skill) to implement each style. I am thus very open to people subtitling and 
sharing their builds.

Due to a technical limitation, the Volcanic Mason needs to be it's onw style, and can not be added to existing styles directly.

## Feedback
In case you have some issues,feedback/ideas or builds you would like to share, feel free to reach out here in the github issues!

## Minecraft 26.1.2 port

This branch targets NeoForge **26.1.2.81** and **Java 25** using the workspace's
ported MineColonies 1.1.1397 stack. See [the port and review report](docs/PORT_REVIEW_RU.md)
for dependency hashes, build instructions, test evidence and remaining manual checks.

Build the release with `./gradlew build`. The release artifact is
`build/libs/volcanicmason-1.0.3-26.1.2.jar`.
The optional `qaJar` task produces a separate destructive-test-world probe; never
install the `-qa.jar` on a normal game server.

Autonomous worker cycles passed the follow-up server probe. Client acceptance is
currently blocked by the local MineColonies artifact loading ClientLevel on a
dedicated server when a player enters the colony. See the
[reproduction and evidence](docs/CLIENT_BLOCKER_RU.md) before using this stack.
