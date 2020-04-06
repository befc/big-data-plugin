/*! ******************************************************************************
 *
 * Pentaho Data Integration
 *
 * Copyright (C) 2019-2020 by Hitachi Vantara : http://www.pentaho.com
 *
 *******************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/

package org.pentaho.big.data.kettle.plugins.formats.impl.orc.output;

import org.apache.commons.io.IOUtils;
import org.apache.commons.vfs2.FileObject;
import org.pentaho.di.core.exception.KettleFileException;
import org.pentaho.hadoop.shim.api.cluster.NamedCluster;
import org.pentaho.hadoop.shim.api.cluster.ClusterInitializationException;
import org.pentaho.di.core.RowMetaAndData;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.row.RowMeta;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.row.value.ValueMetaFactory;
import org.pentaho.di.core.vfs.AliasedFileObject;
import org.pentaho.di.core.vfs.KettleVFS;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.BaseStep;
import org.pentaho.di.trans.step.StepDataInterface;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;
import org.pentaho.hadoop.shim.api.format.FormatService;
import org.pentaho.hadoop.shim.api.format.IPentahoOrcOutputFormat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;

public class OrcOutput extends BaseStep implements StepInterface {

  private OrcOutputMeta meta;

  private OrcOutputData data;

  private String outputFileName;

  private String pvfsFile;

  public OrcOutput( StepMeta stepMeta, StepDataInterface stepDataInterface, int copyNr, TransMeta transMeta,
                    Trans trans ) {
    super( stepMeta, stepDataInterface, copyNr, transMeta, trans );
  }

  @Override
  public synchronized boolean processRow( StepMetaInterface smi, StepDataInterface sdi ) throws KettleException {
    try {
      meta = (OrcOutputMeta) smi;
      data = (OrcOutputData) sdi;

      if ( data.output == null ) {
        init();
      }

      Object[] currentRow = getRow();
      if ( currentRow != null ) {
        //create new outputMeta
        RowMetaInterface outputRMI = new RowMeta();
        //create data equals with output fileds
        Object[] outputData = new Object[ meta.getOutputFields().size() ];
        for ( int i = 0; i < meta.getOutputFields().size(); i++ ) {
          int inputRowIndex = getInputRowMeta().indexOfValue( meta.getOutputFields().get( i ).getPentahoFieldName() );
          if ( inputRowIndex == -1 ) {
            throw new KettleException( "Field name [" + meta.getOutputFields().get( i ).getPentahoFieldName()
              + " ] couldn't be found in the input stream!" );
          } else {
            ValueMetaInterface vmi = ValueMetaFactory.cloneValueMeta( getInputRowMeta().getValueMeta( inputRowIndex ) );
            //add output value meta according output fields
            outputRMI.addValueMeta( i, vmi );
            //add output data according output fields
            outputData[ i ] = currentRow[ inputRowIndex ];
          }
        }
        RowMetaAndData row = new RowMetaAndData( outputRMI, outputData );
        data.writer.write( row );
        putRow( row.getRowMeta(), row.getData() );
        return true;
      } else {
        // no more input to be expected...
        closeWriter();
        copyFileAndDeleteTempSource();
        setOutputDone();
        return false;
      }
    } catch ( IllegalStateException e ) {
      getLogChannel().logError( e.getMessage() );
      setErrors( 1 );
      setOutputDone();
      return false;
    } catch ( KettleException ex ) {
      throw ex;
    } catch ( Exception ex ) {
      throw new KettleException( ex );
    }
  }

  public void init() throws Exception {
    FormatService formatService;
    try {
      formatService = meta.getNamedClusterResolver().getNamedClusterServiceLocator()
        .getService( getNamedCluster(), FormatService.class );
    } catch ( ClusterInitializationException e ) {
      throw new KettleException( "can't get service format shim ", e );
    }

    if ( meta.getFilename() == null ) {
      throw new KettleException( "No output files defined" );
    }

    data.output = formatService.createOutputFormat( IPentahoOrcOutputFormat.class, getNamedCluster() );

    outputFileName = environmentSubstitute( meta.constructOutputFilename() );
    FileObject outputFileObject = KettleVFS.getFileObject( outputFileName, getTransMeta() );
    if ( AliasedFileObject.isAliasedFile( outputFileObject ) ) {
      outputFileName = ( (AliasedFileObject) outputFileObject ).getOriginalURIString();
    }

    //See if we need to use a another URI because the HadoopFileSystem is not supported for this URL.
    String aliasedFile = data.output.generateAlias( outputFileName );
    if ( aliasedFile != null ) {
      if ( outputFileObject.exists() ) {
        if ( meta.isOverrideOutput() ) {
          outputFileObject.delete();
        } else {
          throw new FileAlreadyExistsException( outputFileName );
        }
      }
      pvfsFile = outputFileName;  //Save the original pvfs final destination for later use
      outputFileName = aliasedFile;  //set the outputFile to the temporary alias file
    }

    data.output.setOutputFile( outputFileName, meta.isOverrideOutput() );
    data.output.setFields( meta.getOutputFields() );

    IPentahoOrcOutputFormat.COMPRESSION compression;
    try {
      compression = IPentahoOrcOutputFormat.COMPRESSION.valueOf( meta.getCompressionType().toUpperCase() );
    } catch ( Exception ex ) {
      compression = IPentahoOrcOutputFormat.COMPRESSION.NONE;
    }
    data.output.setCompression( compression );
    if ( compression != IPentahoOrcOutputFormat.COMPRESSION.NONE ) {
      data.output.setCompressSize( meta.getCompressSize() );
    }
    data.output.setRowIndexStride( meta.getRowsBetweenEntries() );
    data.output.setStripeSize( meta.getStripeSize() );
    data.writer = data.output.createRecordWriter();
  }

  private NamedCluster getNamedCluster() {
    return meta.getNamedClusterResolver().resolveNamedCluster( environmentSubstitute( meta.getFilename() ) );
  }

  public void closeWriter() throws KettleException {
    try {
      data.writer.close();
    } catch ( IOException e ) {
      throw new KettleException( e );
    }
    data.output = null;
  }

  public boolean init( StepMetaInterface smi, StepDataInterface sdi ) {
    meta = (OrcOutputMeta) smi;
    data = (OrcOutputData) sdi;
    return super.init( smi, sdi );
  }

  private void copyFileAndDeleteTempSource( ) throws KettleFileException, IOException {
    // if pvfsFile is present the assumption is we used a temporary file with hadoop and must now copy the file to
    // its final destination.
    if ( pvfsFile != null ) {
      FileObject srcFile = KettleVFS.getFileObject( outputFileName, getTransMeta() );
      FileObject destFile = KettleVFS.getFileObject( pvfsFile, getTransMeta() );
      try ( InputStream in = KettleVFS.getInputStream( srcFile );
            OutputStream out = KettleVFS.getOutputStream( destFile, false ) ) {
        IOUtils.copy( in, out );
      }
      deleteTempFileAndFolder();
    }
  }

  private void deleteTempFileAndFolder( ) throws KettleFileException, IOException {
    if ( pvfsFile != null ) {
      FileObject srcFile = KettleVFS.getFileObject( outputFileName, getTransMeta() );
      srcFile.getParent().deleteAll();
    }
  }
}
