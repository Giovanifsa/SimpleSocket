// 
// Decompiled by Procyon v0.5.36
// 

package rede;

public interface SocketHook
{
    void pedidoRecebido(final PacoteRede p0);
    
    void erroFatal(final Exception p0);
    
    void conexaoTerminada(final String p0);
}
