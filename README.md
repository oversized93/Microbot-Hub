# Microbot-Hub
Create your Microbot Plugins in the Microbot Hub


# 1. Project Setup

## 1.1 Open gradle

![img.png](img.png)

## 2.1 Reload all gradle projects

![img_1.png](img_1.png)

You are now ready to execute gradle commands. Gradle allows us to build our plugins and run microbot.

# 2. How to run Microbot with a plugin

![img_2.png](img_2.png)

### 2.1 Build & Run a specific plugin(s). Replace `exampletest` with your plugin name.
```bash
gradle build -PpluginList=exampletest
```
### pluginList parameter accepts a comma seperated list of plugin names. For example, if you have two plugins named `exampletest` and `exampletest2`, you can build them both with:
```bash
gradle build -PpluginList=exampletest,exampletest2
```

The build command will 
1. shade jar all the specified plugins and copy them to the .runelite/microbot-plugins folder. 
2. start the microbot client with the specified plugins.



