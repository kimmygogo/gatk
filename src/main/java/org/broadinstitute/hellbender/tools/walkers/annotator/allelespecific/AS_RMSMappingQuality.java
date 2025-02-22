package org.broadinstitute.hellbender.tools.walkers.annotator.allelespecific;

import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.GenotypesContext;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFConstants;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.engine.ReferenceContext;
import org.broadinstitute.hellbender.tools.walkers.annotator.AnnotationUtils;
import org.broadinstitute.hellbender.tools.walkers.annotator.InfoFieldAnnotation;
import org.broadinstitute.hellbender.utils.QualityUtils;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.genotyper.AlleleLikelihoods;
import org.broadinstitute.hellbender.utils.help.HelpConstants;
import org.broadinstitute.hellbender.utils.logging.OneShotLogger;
import org.broadinstitute.hellbender.utils.read.GATKRead;
import org.broadinstitute.hellbender.utils.variant.GATKVCFConstants;

import java.util.*;


/**
 * Allele-specific Root Mean Square of the mapping quality of reads across all samples.
 *
 * <p>This annotation provides an estimation of the mapping quality of reads supporting each alternate allele in a variant call. Depending on the tool it is called from, it produces either raw data (sum of squared MQs) or the calculated root mean square.</p>
 *
 * The raw data is used to accurately calculate the root mean square when combining more than one sample.
 *
 * <h3>Statistical notes</h3>
 * <p>The root mean square is equivalent to the mean of the mapping qualities plus the standard deviation of the mapping qualities.</p>
 *
 * <h3>Related annotations</h3>
 * <ul>
 *     <li><b><a href="https://www.broadinstitute.org/gatk/guide/tooldocs/org_broadinstitute_gatk_tools_walkers_annotator_MappingQualityRankSumTest.php">MappingQualityRankSumTest</a></b> compares the mapping quality of reads supporting the REF and ALT alleles.</li>
 * </ul>
 *
 * <h3>Caveat</h3>
 * <p>Uninformative reads are not used in this annotation.</p>
 *
 * <h3>Related annotations</h3>
 * <ul>
 *     <li><b>RMSMappingQuality</b> outputs a version of this annotation that includes all alternate alleles in a single calculation.</li>
 *     <li><b>MappingQualityRankSumTest</b> compares the mapping quality of reads supporting the REF and ALT alleles.</li>
 * </ul>
 */
@DocumentedFeature(groupName=HelpConstants.DOC_CAT_ANNOTATORS, groupSummary=HelpConstants.DOC_CAT_ANNOTATORS_SUMMARY, summary="Allele-specific root-mean-square of the mapping quality of reads across all samples (AS_MQ)")
public final class AS_RMSMappingQuality implements InfoFieldAnnotation, AS_StandardAnnotation, ReducibleAnnotation, AlleleSpecificAnnotation {

    private final String printFormat = "%.2f";

    private static final OneShotLogger allele_logger = new OneShotLogger(AS_RMSMappingQuality.class);
    private static final OneShotLogger genotype_logger = new OneShotLogger(AS_RMSMappingQuality.class);

    @Override
    public String getEmptyRawValue() {
        return "0.00";
    }

    @Override
    public String getPrimaryRawKey() { return GATKVCFConstants.AS_RAW_RMS_MAPPING_QUALITY_KEY; }

    /**
     * @return true if annotation has secondary raw keys
     */
    @Override
    public boolean hasSecondaryRawKeys() {
        return false;
    }

    /**
     * Get additional raw key strings that are not the primary key
     *
     * @return may be null
     */
    @Override
    public List<String> getSecondaryRawKeys() {
        return null;
    }

    @Override
    public Map<String, Object> annotate(final ReferenceContext ref,
                                        final VariantContext vc,
                                        final AlleleLikelihoods<GATKRead, Allele> likelihoods) {
        Utils.nonNull(vc);
        if ( likelihoods == null) {
            return Collections.emptyMap();
        }
        final Map<String, Object> annotations = new HashMap<>();
        final ReducibleAnnotationData<Double> myData = new ReducibleAnnotationData<>(null);
        getRMSDataFromLikelihoods(likelihoods, myData);
        final String annotationString = makeFinalizedAnnotationString(vc, myData.getAttributeMap());
        annotations.put(getKeyNames().get(0), annotationString);
        return annotations;
    }


    @Override
    public Map<String, Object> annotateRawData(final ReferenceContext ref,
                                               final VariantContext vc,
                                               final AlleleLikelihoods<GATKRead, Allele> likelihoods ) {
        Utils.nonNull(vc);
        if ( likelihoods == null) {
            return Collections.emptyMap();
        }

        final Map<String, Object> annotations = new LinkedHashMap<>();
        final ReducibleAnnotationData<Double> myData = new ReducibleAnnotationData<>(null);
        getRMSDataFromLikelihoods(likelihoods, myData);
        final String annotationString = makeRawAnnotationString(vc.getAlleles(), myData.getAttributeMap());
        annotations.put(getPrimaryRawKey(), annotationString);
        return annotations;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})//FIXME
    public void calculateRawData(final VariantContext vc,
                                 final AlleleLikelihoods<GATKRead, Allele> likelihoods,
                                 final ReducibleAnnotationData myData){
        //For the raw data here, we're only keeping track of the sum of the squares of our values
        //When we go to reduce, we'll use the AD info to get the number of reads

        //must use likelihoods for allele-specific annotations
        if (likelihoods == null) {
            return;
        }
        getRMSDataFromLikelihoods(likelihoods, myData);
    }


    /**
     * For AS_RMSMappingQuality annotations, the annotations will simply consist of a list of the total value for
     * every allele computed by parsing all of the individual AS_RMSMappingQuality Raw Key values as doubles
     * and totaling them.
     */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})//FIXME generics here blow up
    public Map<String, Object> combineRawData(final List<Allele> vcAlleles, final List<ReducibleAnnotationData<?>>  annotationList) {
        //VC already contains merged alleles from ReferenceConfidenceVariantContextMerger
        ReducibleAnnotationData<Double> combinedData = new AlleleSpecificAnnotationData(vcAlleles, null);

        for (final ReducibleAnnotationData<?> currentValue : annotationList) {
            ReducibleAnnotationData<Double> value = (ReducibleAnnotationData<Double>)currentValue;
            parseRawDataString(value);
            combineAttributeMap(value, combinedData);

        }
        final Map<String, Object> annotations = new HashMap<>();
        String annotationString = makeRawAnnotationString(vcAlleles, combinedData.getAttributeMap());
        annotations.put(getPrimaryRawKey(), annotationString);
        return annotations;
    }

    public void combineAttributeMap(final ReducibleAnnotationData<Double> toAdd, final ReducibleAnnotationData<Double> combined) {
        //check that alleles match
        for (final Allele currentAllele : combined.getAlleles()){
            //combined is initialized with all alleles, but toAdd might have only a subset
            if(toAdd.getAttribute(currentAllele) != null) {
                if (toAdd.getAttribute(currentAllele) != null && combined.getAttribute(currentAllele) != null) {
                    combined.putAttribute(currentAllele, (double) combined.getAttribute(currentAllele) + (double) toAdd.getAttribute(currentAllele));
                } else {
                    combined.putAttribute(currentAllele, toAdd.getAttribute(currentAllele));
                }
            }
        }
    }

    /**
     *
     * @param myData    may contain null values
     */
    protected void parseRawDataString(final ReducibleAnnotationData<Double> myData) {
        final String rawDataString = myData.getRawData();
        //get per-allele data by splitting on allele delimiter
        final String[] rawDataPerAllele = rawDataString.split(AnnotationUtils.ALLELE_SPECIFIC_SPLIT_REGEX);
        for (int i=0; i<rawDataPerAllele.length; i++) {
            final String alleleData = rawDataPerAllele[i];
            if (alleleData.isEmpty()) {
                myData.putAttribute(myData.getAlleles().get(i), null);
            } else {
                myData.putAttribute(myData.getAlleles().get(i), Double.parseDouble(alleleData));
            }
        }
    }


    /**
     * Takes combined raw annotation data sums, and calculates per allele the average root mean squared from the raw data
     * using expected Allele Depth counts data.
     *
     * Will output delineated doubles in the format: sqrt(TotalAllele1RMS/Allele1Depth)|sqrt(TotalAllele1RMS/Allele1Depth)|...
     */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})//FIXME generics here blow up
    public Map<String, Object> finalizeRawData(final VariantContext vc, final VariantContext originalVC) {
        if (!vc.hasAttribute(getPrimaryRawKey())) {
            return new HashMap<>();
        }
        final String rawMQdata = vc.getAttributeAsString(getPrimaryRawKey(),null);
        if (rawMQdata == null) {
            return new HashMap<>();
        }

        final Map<String,Object> annotations = new HashMap<>();
        final ReducibleAnnotationData myData = new AlleleSpecificAnnotationData<Double>(originalVC.getAlleles(), rawMQdata);
        parseRawDataString(myData);

        final String annotationString = makeFinalizedAnnotationString(vc, myData.getAttributeMap());
        annotations.put(getKeyNames().get(0), annotationString);
        annotations.put(getPrimaryRawKey(), makeRawAnnotationString(vc.getAlleles(), myData.getAttributeMap()));
        return annotations;
    }


    @Override
    public List<String> getKeyNames() { return Arrays.asList(GATKVCFConstants.AS_RMS_MAPPING_QUALITY_KEY); }

    private void getRMSDataFromLikelihoods(final AlleleLikelihoods<GATKRead, Allele> likelihoods, ReducibleAnnotationData<Double> myData) {
        for ( final AlleleLikelihoods<GATKRead, Allele>.BestAllele bestAllele : likelihoods.bestAllelesBreakingTies() ) {
            if (bestAllele.isInformative()) {
                final int mq = bestAllele.evidence.getMappingQuality();
                if ( mq != QualityUtils.MAPPING_QUALITY_UNAVAILABLE ) {
                    final double currSquareSum = myData.hasAttribute(bestAllele.allele) ? (double) myData.getAttribute(bestAllele.allele) : 0;
                    myData.putAttribute(bestAllele.allele, currSquareSum + mq * mq);
                }
            }
        }
    }

    private String makeRawAnnotationString(final List<Allele> vcAlleles, final Map<Allele, Double> perAlleleValues) {
        String annotationString = "";
        for (final Allele current : vcAlleles) {
            if (!annotationString.isEmpty()) {
                annotationString += AnnotationUtils.ALLELE_SPECIFIC_RAW_DELIM;
            }
            if(perAlleleValues.get(current) != null) {
                annotationString += String.format(printFormat, perAlleleValues.get(current));
            } else {
                annotationString += String.format(printFormat, 0.0);
            }
        }
        return annotationString;
    }

    private String makeFinalizedAnnotationString(final VariantContext vc, final Map<Allele, Double> perAlleleValues) {
        final Map<Allele, Integer> variantADs = getADcounts(vc);
        String annotationString = "";
        for (final Allele current : vc.getAlternateAlleles()) {
            if (!annotationString.isEmpty()) {
                annotationString += ",";
            }
            if (perAlleleValues.containsKey(current)) {
                if (perAlleleValues.get(current) == null) {
                    annotationString += VCFConstants.MISSING_VALUE_v4;
                } else {
                    annotationString += String.format(printFormat, Math.sqrt((double) perAlleleValues.get(current) / variantADs.get(current)));
                }
            } else {
                allele_logger.warn("ERROR: VC allele is not found in annotation alleles -- maybe there was trimming?");
            }
        }
        return annotationString;
    }

    private Map<Allele, Integer> getADcounts(final VariantContext vc) {
        final GenotypesContext genotypes = vc.getGenotypes();
        if ( genotypes == null || genotypes.size() == 0 ) {
            genotype_logger.warn("VC does not have genotypes -- annotations were calculated in wrong order");
            return null;
        }

        final Map<Allele, Integer> variantADs = new HashMap<>();
        for(final Allele a : vc.getAlleles())
            variantADs.put(a,0);

        for (final Genotype gt : vc.getGenotypes()) {
            if(gt.hasAD()) {
                final int[] ADs = gt.getAD();
                for (int i = 1; i < vc.getNAlleles(); i++) {
                    variantADs.put(vc.getAlternateAllele(i - 1), variantADs.get(vc.getAlternateAllele(i - 1)) + ADs[i]); //here -1 is to reconcile allele index with alt allele index
                }
            }
        }
        return variantADs;
    }
}
