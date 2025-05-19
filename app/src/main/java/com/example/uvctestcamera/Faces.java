package com.example.uvctestcamera;

import android.annotation.SuppressLint;

public interface Faces {
    class Recognition{
        private final String id;
        private final String name;
        private final Float distance;
        private Object extra;

        public Recognition(String id, String name, Float distance) {
            this.id = id;
            this.name = name;
            this.distance = distance;
            this.extra = null;
        }

        public Object getExtra(){
            return this.extra;
        }

        public void setExtra(Object extra){
            this.extra = extra;
        }

//        public void setDistance(float distance) {
//            this.distance = distance;
//        }
//        public void getDistance(float distance) {
//            return this.distance;
//        }

        @Override
        public String toString(){
            String title = "";
            if(this.id != null){
                title += ("[" +id+"]");
            }
            if(this.name != null){
                title += " " + name;
            }
            if(this.distance != null){
                title += " " + String.format("(%.1f%%) ", distance * 100.0f);
            }
            return title.trim();
        }
    }
}

