package com.nextaicommerce.platform.marketplace;

/** One marketplace-domain policy for table rows, catalogue mappings and inventory drawers. */
public final class MarketplaceLinks {
    private MarketplaceLinks(){}
    public static String amazonDomain(String marketplace){
        if(marketplace==null)return "amazon.com";
        return switch(marketplace){
            case "A2EUQ1WTGCTBG2" -> "amazon.ca";case "A1AM78C64UM0Y8" -> "amazon.com.mx";
            case "A1F83G8C2ARO7P" -> "amazon.co.uk";case "A1PA6795UKMFR9" -> "amazon.de";
            case "A13V1IB3VIYZZH" -> "amazon.fr";case "APJ6JRA9NG5V4" -> "amazon.it";
            case "A1RKKUPIHCS9HS" -> "amazon.es";case "A1805IZSGTT6HS" -> "amazon.nl";
            case "A2NODRKZP88ZB9" -> "amazon.se";case "A1C3SOZRARQ6R3" -> "amazon.pl";
            case "AMEN7PMS3EDWL" -> "amazon.com.be";case "A1VC38T7YXB528" -> "amazon.co.jp";
            case "A39IBJ37TRP1C6" -> "amazon.com.au";case "A21TJRUUN4KGV" -> "amazon.in";
            default -> "amazon.com";
        };
    }
}
