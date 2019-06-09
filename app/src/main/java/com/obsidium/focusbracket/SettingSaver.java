package com.obsidium.focusbracket;

import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class SettingSaver {

    public static File getFile() {
        return new File(Environment.getExternalStorageDirectory(), "ULTRABRK/SETTINGS.TXT");
    }

    private static JSONArray toJsonIntArray(List<Integer> ints) {
        JSONArray arr = new JSONArray();
        for(Integer item: ints) {
            arr.put(item);
        }
        return arr;
    }

    private static ArrayList<Integer> fromJsonIntArray(JSONArray arr) {
        ArrayList<Integer> ints = new ArrayList<Integer>();
        for(int i=0; i<arr.length(); i++) {
            ints.add(arr.optInt(i, -1));
        }
        return ints;
    }

    public static void save(ShootSettings shootSettings) {
        try {
            JSONObject obj = new JSONObject();

            obj.put("exposureBracket", shootSettings.exposureBracket);
            obj.put("focusPoints", toJsonIntArray(shootSettings.focusPoints));

            String settingString = obj.toString(2);

            getFile().getParentFile().mkdirs();
            BufferedWriter writer = new BufferedWriter(new FileWriter(getFile(), false));
            writer.write(settingString);
            writer.flush();
            writer.close();
        } catch (Exception e) {}
    }

    public static ShootSettings load() {
        ShootSettings settings = new ShootSettings();

        try {
            File settingFile = getFile();
            if(settingFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(settingFile));
                StringBuilder builder = new StringBuilder();
                String line = reader.readLine();
                while(line != null) {
                    builder.append(line);
                    line = reader.readLine();
                }
                reader.close();

                String settingString = builder.toString();
                JSONObject obj = new JSONObject(settingString);

                settings.exposureBracket = obj.optInt("exposureBracket", settings.exposureBracket);

                JSONArray focusPointsArray = obj.optJSONArray("focusPoints");
                if(focusPointsArray != null) {
                    settings.focusPoints = fromJsonIntArray(focusPointsArray);
                }
            }
        } catch (Exception e) {}

        return settings;
    }

}
