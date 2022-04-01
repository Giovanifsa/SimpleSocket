// 
// Decompiled by Procyon v0.5.36
// 

package rede;

import java.io.Serializable;

public class PacoteRede implements Serializable
{
    private int identificacaoPacote;
    private Object objetoEnvio;
    private boolean resposta;
    private int indicePacote;
    private long timeStampRecebido;
    
    public PacoteRede(final int id) {
        this.objetoEnvio = null;
        this.resposta = false;
        this.timeStampRecebido = 0L;
        this.identificacaoPacote = id;
    }
    
    public PacoteRede(final int id, final Object dado) {
        this(id);
        this.objetoEnvio = dado;
    }
    
    public PacoteRede() {
        this.objetoEnvio = null;
        this.resposta = false;
        this.timeStampRecebido = 0L;
        this.resposta = true;
    }
    
    public PacoteRede(final Object dado) {
        this();
        this.objetoEnvio = dado;
    }
    
    public int getID() {
        return this.identificacaoPacote;
    }
    
    public boolean isResposta() {
        return this.resposta;
    }
    
    public void setDado(final Object dado) {
        this.objetoEnvio = dado;
    }
    
    public Object getDado() {
        return this.objetoEnvio;
    }
    
    public int getIndice() {
        return this.indicePacote;
    }
    
    public void setIndice(final int indice) {
        this.indicePacote = indice;
    }
    
    public void setHorarioRecebido(final long timestamp) {
        this.timeStampRecebido = timestamp;
    }
    
    public long getHorarioRecebido() {
        return this.timeStampRecebido;
    }
}
