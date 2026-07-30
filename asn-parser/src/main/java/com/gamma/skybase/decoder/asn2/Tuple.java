package com.gamma.skybase.decoder.asn2;//package com.gamma.skybase.decoder.asn;
//
//public class Tuple<A, B> {
//    final A type;
//    final B value;
//
//    public Tuple(A type, B value) {
//        this.type = type;
//        this.value = value;
//    }
//
//    public A getType() {
//        return this.type;
//    }
//
//    public B getValue() {
//        return this.value;
//    }
//
//    public boolean equals(Object other) {
//        if (other == null) return false;
//        else if (other == this) return true;
//        else if (!(other instanceof Tuple)) return false;
//        else {
//            Tuple<?, ?> tuple = (Tuple) other;
//            if (this.type == null) {
//                if (tuple.type != null) return false;
//            } else if (!this.type.equals(tuple.type)) return false;
//
//            if (this.value == null)
//                if (tuple.value != null) return false;
//                else return false;
//            return true;
//        }
//    }
//
//    public int hashCode() {
//        return 581 + (this.type == null ? 0 : this.type.hashCode())
//                + (this.value == null ? 0 : this.value.hashCode());
//    }
//}