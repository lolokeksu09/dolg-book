package dev.ventry.raschet;

/** Local phone lookup: formatted digits and Russian domestic/international prefixes. */
final class ClientSearch {
    private ClientSearch(){}
    static boolean phoneMatches(String stored,String query){
        if(query==null||!query.matches("[0-9+() .\\-]+"))return false;
        String wanted=query.replaceAll("[^0-9]","");
        if(wanted.isEmpty())return false;
        String actual=stored==null?"":stored.replaceAll("[^0-9]","");
        if(actual.contains(wanted))return true;
        // Only equate complete Russian numbers; do not rewrite other country codes.
        return russian(wanted)&&russian(actual)&&wanted.substring(1).equals(actual.substring(1));
    }
    private static boolean russian(String digits){
        return digits.length()==11&&(digits.charAt(0)=='7'||digits.charAt(0)=='8');
    }
}
